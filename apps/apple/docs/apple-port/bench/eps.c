#include <stdio.h>
#include <string.h>
#include "onnxruntime_c_api.h"

int main(void) {
  const OrtApi* g = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  printf("ORT build version: %s   (header ORT_API_VERSION=%d)\n",
         OrtGetApiBase()->GetVersionString(), ORT_API_VERSION);
  char** provs; int n;
  g->GetAvailableProviders(&provs, &n);
  printf("GetAvailableProviders (%d):\n", n);
  for (int i = 0; i < n; i++) printf("  - %s\n", provs[i]);
  g->ReleaseAvailableProviders(provs, n);

  // Probe generic append for each candidate EP name.
  const char* names[] = {"XNNPACK", "CoreML", "CPU", "NNAPI", "WEBGPU"};
  for (unsigned i = 0; i < sizeof(names)/sizeof(*names); i++) {
    OrtSessionOptions* so; g->CreateSessionOptions(&so);
    OrtStatus* st = g->SessionOptionsAppendExecutionProvider(so, names[i], NULL, NULL, 0);
    printf("SessionOptionsAppendExecutionProvider(\"%s\") -> %s\n", names[i],
           st ? g->GetErrorMessage(st) : "OK");
    if (st) g->ReleaseStatus(st);
    g->ReleaseSessionOptions(so);
  }
  return 0;
}
