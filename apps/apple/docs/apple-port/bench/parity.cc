// htdemucs numerical parity: fp32 CPU gold vs fp16-weights graph on CPU / CoreML.
// Identical deterministic inputs for every config.
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <chrono>
#include <vector>
#include <string>
#include <cmath>
#include <algorithm>
#include <unordered_map>
#include "onnxruntime_cxx_api.h"

using clk = std::chrono::steady_clock;
static double ms_since(clk::time_point t){return std::chrono::duration<double,std::milli>(clk::now()-t).count();}
static const int SEG=114660, BINS=2048, LE=112;

struct Cfg { const char* label; const char* model; const char* ep; int th; std::unordered_map<std::string,std::string> opt; };

static void fill(std::vector<float>& v, unsigned seed) {
  unsigned s = seed;
  for (auto& x : v) { s = s*1664525u + 1013904223u; x = ((s>>8)/(float)0xFFFFFF - 0.5f) * 0.2f; }
}

int main(int argc, char** argv) {
  std::string dir = argv[1];              // scratchpad dir holding htdemucs_fp32.onnx
  std::string f16 = argv[2];              // path to htdemucs_s26_f16.onnx
  std::string fp32 = dir + "/htdemucs_fp32.onnx";
  Ort::Env env(ORT_LOGGING_LEVEL_ERROR, "parity");

  std::vector<float> wav(2*SEG), spec(4*BINS*LE);
  fill(wav, 12345); fill(spec, 999);

  std::vector<Cfg> cfgs = {
    {"fp32 graph  CPU t=1  [GOLD]", fp32.c_str(), "cpu", 1, {}},
    {"fp32 graph  CPU t=8",         fp32.c_str(), "cpu", 8, {}},
    {"fp16 graph  CPU t=1",         f16.c_str(),  "cpu", 1, {}},
    {"fp16 graph  CPU t=8",         f16.c_str(),  "cpu", 8, {}},
    {"fp16 graph  CoreML GPU",      f16.c_str(),  "CoreML", 6, {{"MLComputeUnits","CPUAndGPU"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"},{"ModelCacheDirectory", dir+"/pc_gpu"}}},
    {"fp16 graph  CoreML ALL",      f16.c_str(),  "CoreML", 6, {{"MLComputeUnits","ALL"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"},{"ModelCacheDirectory", dir+"/pc_all"}}},
    {"fp32 graph  CoreML GPU",      fp32.c_str(), "CoreML", 6, {{"MLComputeUnits","CPUAndGPU"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"},{"ModelCacheDirectory", dir+"/pc_gpu32"}}},
  };

  std::vector<float> g_spec, g_wave;
  for (auto& c : cfgs) {
    try {
      Ort::SessionOptions so;
      so.SetIntraOpNumThreads(c.th);
      so.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
      so.AddConfigEntry("session.intra_op.allow_spinning", "0");
      so.DisableCpuMemArena(); so.DisableMemPattern();
      if (strcmp(c.ep,"cpu")!=0) so.AppendExecutionProvider(c.ep, c.opt);
      auto t0 = clk::now();
      Ort::Session sess(env, c.model, so);
      double load = ms_since(t0);

      Ort::AllocatorWithDefaultOptions al;
      std::vector<std::string> ins, outs;
      for (size_t i=0;i<sess.GetInputCount();i++) ins.push_back(sess.GetInputNameAllocated(i,al).get());
      for (size_t i=0;i<sess.GetOutputCount();i++) outs.push_back(sess.GetOutputNameAllocated(i,al).get());
      std::vector<const char*> ip, op;
      for (auto&s:ins) ip.push_back(s.c_str());
      for (auto&s:outs) op.push_back(s.c_str());
      auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
      int64_t sw[3]={1,2,SEG}, ss[4]={1,4,BINS,LE};
      std::vector<float> w = wav, sp = spec;   // fresh identical copies
      std::vector<Ort::Value> iv;
      iv.push_back(Ort::Value::CreateTensor<float>(mem, w.data(), w.size(), sw, 3));
      iv.push_back(Ort::Value::CreateTensor<float>(mem, sp.data(), sp.size(), ss, 4));

      std::vector<double> ts; std::vector<float> o_spec, o_wave;
      for (int r=0;r<4;r++) {
        auto t=clk::now();
        auto o = sess.Run(Ort::RunOptions{nullptr}, ip.data(), iv.data(), iv.size(), op.data(), op.size());
        double e = ms_since(t); if (r>0) ts.push_back(e);
        if (r==3) for (auto& v : o) { auto in=v.GetTensorTypeAndShapeInfo(); size_t n=in.GetElementCount();
          const float* d=v.GetTensorData<float>(); (in.GetShape().size()==5?o_spec:o_wave).assign(d,d+n); }
      }
      std::sort(ts.begin(),ts.end());
      size_t nan_spec=0, nan_wave=0;
      for (float f:o_spec) if(!std::isfinite(f)) nan_spec++;
      for (float f:o_wave) if(!std::isfinite(f)) nan_wave++;
      if (g_spec.empty()) { g_spec=o_spec; g_wave=o_wave; }
      auto snr=[](const std::vector<float>&a,const std::vector<float>&b){
        if(a.size()!=b.size()||a.empty()) return -999.0; double s=0,d=0;
        for(size_t i=0;i<a.size();i++){s+=(double)a[i]*a[i];double e=(double)a[i]-b[i];d+=e*e;}
        return 10.0*log10(s/std::max(d,1e-30)); };
      printf("%-30s load=%7.0fms  median=%8.1fms  rt=%5.2fx  nonFinite=%zu/%zu  SNRvsGOLD spec=%6.1fdB wave=%6.1fdB\n",
        c.label, load, ts[ts.size()/2], (SEG/44100.0)/(ts[ts.size()/2]/1000.0), nan_spec, nan_wave,
        snr(g_spec,o_spec), snr(g_wave,o_wave));
      fflush(stdout);
    } catch (const std::exception& e) { printf("%-30s FAILED: %.180s\n", c.label, e.what()); fflush(stdout); }
  }
  return 0;
}
