// Generic ORT 1.24.2 (Apple SPM slice) benchmark: any model with static shapes.
// usage: gen <model.onnx> <reps> [cachedir]
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <vector>
#include <string>
#include <algorithm>
#include <cmath>
#include <unordered_map>
#include "onnxruntime_cxx_api.h"

using clk = std::chrono::steady_clock;
static double ms_since(clk::time_point t){return std::chrono::duration<double,std::milli>(clk::now()-t).count();}

struct Case { const char* label; const char* ep; int threads; std::unordered_map<std::string,std::string> opt; };

int main(int argc, char** argv) {
  const char* path = argv[1];
  int reps = argc > 2 ? atoi(argv[2]) : 30;
  std::string cache = argc > 3 ? argv[3] : "";

  Ort::Env env(ORT_LOGGING_LEVEL_ERROR, "gen");
  std::vector<Case> cases = {
    {"CPU t=1", "cpu", 1, {}},
    {"CPU t=2", "cpu", 2, {}},
    {"CPU t=4", "cpu", 4, {}},
    {"XNNPACK t=2", "XNNPACK", 1, {{"intra_op_num_threads","2"}}},
    {"XNNPACK t=4", "XNNPACK", 1, {{"intra_op_num_threads","4"}}},
    {"CoreML ALL/MLProgram", "CoreML", 1, {{"MLComputeUnits","ALL"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML ANE/MLProgram", "CoreML", 1, {{"MLComputeUnits","CPUAndNeuralEngine"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML GPU/MLProgram", "CoreML", 1, {{"MLComputeUnits","CPUAndGPU"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML ALL/NeuralNet", "CoreML", 1, {{"MLComputeUnits","ALL"},{"ModelFormat","NeuralNetwork"},{"RequireStaticInputShapes","1"}}},
  };

  std::vector<std::vector<float>> ref_out;
  printf("== %s\n", path);
  for (auto& c : cases) {
    try {
      Ort::SessionOptions so;
      so.SetIntraOpNumThreads(c.threads);
      so.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
      so.AddConfigEntry("session.intra_op.allow_spinning", "0");
      auto opt = c.opt;
      if (!cache.empty() && strcmp(c.ep,"CoreML")==0) opt["ModelCacheDirectory"] = cache + "/" + c.label;
      if (strcmp(c.ep,"cpu") != 0) so.AppendExecutionProvider(c.ep, opt);

      auto t0 = clk::now();
      Ort::Session sess(env, path, so);
      double load = ms_since(t0);

      Ort::AllocatorWithDefaultOptions alloc;
      std::vector<std::string> ins, outs;
      std::vector<std::vector<int64_t>> shapes;
      std::vector<std::vector<float>> bufs;
      for (size_t i = 0; i < sess.GetInputCount(); i++) {
        ins.push_back(sess.GetInputNameAllocated(i, alloc).get());
        auto sh = sess.GetInputTypeInfo(i).GetTensorTypeAndShapeInfo().GetShape();
        for (auto& d : sh) if (d < 0) d = 1;   // pin any leftover dynamic dim to 1
        size_t n = 1; for (auto d : sh) n *= (size_t)d;
        std::vector<float> b(n);
        for (auto& v : b) v = rand()/(float)RAND_MAX;
        shapes.push_back(sh); bufs.push_back(std::move(b));
      }
      for (size_t i = 0; i < sess.GetOutputCount(); i++) outs.push_back(sess.GetOutputNameAllocated(i, alloc).get());
      std::vector<const char*> inp, outp;
      for (auto& s : ins) inp.push_back(s.c_str());
      for (auto& s : outs) outp.push_back(s.c_str());
      auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
      std::vector<Ort::Value> iv;
      for (size_t i = 0; i < bufs.size(); i++)
        iv.push_back(Ort::Value::CreateTensor<float>(mem, bufs[i].data(), bufs[i].size(), shapes[i].data(), shapes[i].size()));

      std::vector<double> ts; std::vector<std::vector<float>> got;
      for (int r = 0; r < reps + 3; r++) {
        auto t = clk::now();
        auto o = sess.Run(Ort::RunOptions{nullptr}, inp.data(), iv.data(), iv.size(), outp.data(), outp.size());
        double e = ms_since(t);
        if (r >= 3) ts.push_back(e);
        if (r == reps + 2) { got.clear();
          for (auto& v : o) { size_t n = v.GetTensorTypeAndShapeInfo().GetElementCount();
            const float* d = v.GetTensorData<float>(); got.push_back(std::vector<float>(d, d+n)); } }
      }
      std::sort(ts.begin(), ts.end());
      if (ref_out.empty()) ref_out = got;
      double maxabs = 0; bool cmp = ref_out.size()==got.size();
      if (cmp) for (size_t i=0;i<got.size();i++) { if (ref_out[i].size()!=got[i].size()) {cmp=false;break;}
        for (size_t j=0;j<got[i].size();j++) maxabs = std::max(maxabs, (double)std::fabs(ref_out[i][j]-got[i][j])); }
      printf("  %-22s load=%7.0fms  median=%8.3fms  min=%8.3fms  maxAbsDiff=%s\n",
             c.label, load, ts[ts.size()/2], ts.front(), cmp ? std::to_string(maxabs).c_str() : "n/a");
      fflush(stdout);
    } catch (const std::exception& e) {
      printf("  %-22s FAILED: %.180s\n", c.label, e.what()); fflush(stdout);
    }
  }
  return 0;
}
