// Benchmark the EXACT ORT build the Swift app links (SPM 1.24.2 macOS slice).
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <chrono>
#include <vector>
#include <string>
#include <algorithm>
#include <cmath>
#include "onnxruntime_cxx_api.h"
#include "coreml_provider_factory.h"

using clk = std::chrono::steady_clock;
static double ms_since(clk::time_point t) {
  return std::chrono::duration<double, std::milli>(clk::now() - t).count();
}

struct Case { const char* label; const char* ep; int threads; std::vector<std::pair<std::string,std::string>> opt; };

int main(int argc, char** argv) {
  const char* path = argv[1];
  const int SEG = 114660, BINS = 2048, LE = 112;
  const int reps = argc > 2 ? atoi(argv[2]) : 5;

  Ort::Env env(ORT_LOGGING_LEVEL_ERROR, "bench");
  std::vector<float> wav(1 * 2 * SEG), spec(1 * 4 * BINS * LE);
  srand(1);
  for (auto& v : wav) v = (rand() / (float)RAND_MAX - 0.5f) * 0.2f;
  for (auto& v : spec) v = (rand() / (float)RAND_MAX - 0.5f) * 0.2f;

  std::vector<Case> cases = {
    {"CPU t=1", "cpu", 1, {}},
    {"CPU t=4", "cpu", 4, {}},
    {"CPU t=6", "cpu", 6, {}},
    {"CPU t=8", "cpu", 8, {}},
    {"XNNPACK t=4", "xnnpack", 1, {{"intra_op_num_threads","4"}}},
    {"XNNPACK t=6", "xnnpack", 1, {{"intra_op_num_threads","6"}}},
    {"CoreML ALL/MLProgram", "coreml", 6, {{"MLComputeUnits","ALL"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML CPUAndGPU/MLProg", "coreml", 6, {{"MLComputeUnits","CPUAndGPU"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML ANE/MLProgram", "coreml", 6, {{"MLComputeUnits","CPUAndNeuralEngine"},{"ModelFormat","MLProgram"},{"RequireStaticInputShapes","1"}}},
    {"CoreML ALL/NeuralNetwork", "coreml", 6, {{"MLComputeUnits","ALL"},{"ModelFormat","NeuralNetwork"},{"RequireStaticInputShapes","1"}}},
  };

  std::vector<float> ref_spec, ref_wave;

  for (auto& c : cases) {
    try {
      Ort::SessionOptions so;
      so.SetIntraOpNumThreads(c.threads);
      so.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
      so.AddConfigEntry("session.intra_op.allow_spinning", "0");
      // Mirrors HtdemucsSession.kt: arena off + memory pattern off.
      so.DisableCpuMemArena();
      so.DisableMemPattern();
      if (strcmp(c.ep, "coreml") == 0 || strcmp(c.ep, "xnnpack") == 0) {
        std::vector<const char*> k, v;
        for (auto& kv : c.opt) { k.push_back(kv.first.c_str()); v.push_back(kv.second.c_str()); }
        so.AppendExecutionProvider(strcmp(c.ep,"coreml")==0 ? "CoreML" : "XNNPACK",
            [&]{ std::unordered_map<std::string,std::string> m; for (auto&kv:c.opt) m[kv.first]=kv.second; return m; }());
      }
      auto t0 = clk::now();
      Ort::Session sess(env, path, so);
      double load = ms_since(t0);

      Ort::AllocatorWithDefaultOptions alloc;
      std::vector<std::string> in_names, out_names;
      for (size_t i = 0; i < sess.GetInputCount(); i++) in_names.push_back(sess.GetInputNameAllocated(i, alloc).get());
      for (size_t i = 0; i < sess.GetOutputCount(); i++) out_names.push_back(sess.GetOutputNameAllocated(i, alloc).get());
      std::vector<const char*> inp, outp;
      for (auto& s : in_names) inp.push_back(s.c_str());
      for (auto& s : out_names) outp.push_back(s.c_str());

      auto mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
      int64_t s_wav[3] = {1,2,SEG}, s_spec[4] = {1,4,BINS,LE};
      std::vector<Ort::Value> ins;
      ins.push_back(Ort::Value::CreateTensor<float>(mem, wav.data(), wav.size(), s_wav, 3));
      ins.push_back(Ort::Value::CreateTensor<float>(mem, spec.data(), spec.size(), s_spec, 4));

      std::vector<double> times;
      std::vector<float> got_spec, got_wave;
      for (int r = 0; r < reps + 1; r++) {
        auto t = clk::now();
        auto outs = sess.Run(Ort::RunOptions{nullptr}, inp.data(), ins.data(), ins.size(), outp.data(), outp.size());
        double e = ms_since(t);
        if (r > 0) times.push_back(e);            // r==0 is warmup
        if (r == reps) {
          for (size_t oi = 0; oi < outs.size(); oi++) {
            auto info = outs[oi].GetTensorTypeAndShapeInfo();
            size_t n = info.GetElementCount();
            const float* d = outs[oi].GetTensorData<float>();
            auto& dst = (info.GetShape().size() == 5) ? got_spec : got_wave;
            dst.assign(d, d + n);
          }
        }
      }
      std::sort(times.begin(), times.end());
      double med = times[times.size()/2];
      bool finite = true;
      for (float f : got_spec) if (!std::isfinite(f)) { finite = false; break; }
      for (float f : got_wave) if (!std::isfinite(f)) { finite = false; break; }
      if (ref_spec.empty()) { ref_spec = got_spec; ref_wave = got_wave; }
      auto snr = [](const std::vector<float>& a, const std::vector<float>& b) {
        if (a.size() != b.size()) return -999.0;
        double s = 0, d = 0;
        for (size_t i = 0; i < a.size(); i++) { s += (double)a[i]*a[i]; double e = (double)a[i]-b[i]; d += e*e; }
        return 10.0 * log10(s / std::max(d, 1e-30));
      };
      printf("%-26s load=%8.0fms  median=%9.1fms  min=%9.1fms  rt=%5.2fx  finite=%d  snr_spec=%7.1fdB snr_wave=%7.1fdB\n",
             c.label, load, med, times.front(), (SEG/44100.0)/(med/1000.0), (int)finite,
             snr(ref_spec, got_spec), snr(ref_wave, got_wave));
      fflush(stdout);
    } catch (const std::exception& e) {
      printf("%-26s FAILED: %.200s\n", c.label, e.what());
      fflush(stdout);
    }
  }
  return 0;
}
