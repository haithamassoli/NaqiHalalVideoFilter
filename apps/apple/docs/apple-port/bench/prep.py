import onnx, sys, os
from onnx import numpy_helper, TensorProto
import numpy as np

M = "/Users/goldentik/AndroidStudioProjects/NaqiHalalVideoFilter/app/src/main/assets/models"
OUT = os.path.dirname(os.path.abspath(__file__))

# 1) htdemucs fp16 -> fp32 (initializers + value_info), for a gold fp32 reference.
try:
    from onnxconverter_common import float16
    m = onnx.load(f"{M}/htdemucs_s26_f16.onnx")
    # convert_float_to_float16 goes the other way; do it manually.
    g = m.graph
    n16 = 0
    for init in g.initializer:
        if init.data_type == TensorProto.FLOAT16:
            arr = numpy_helper.to_array(init).astype(np.float32)
            init.CopyFrom(numpy_helper.from_array(arr, init.name)); n16 += 1
    for vi in list(g.value_info) + list(g.input) + list(g.output):
        if vi.type.tensor_type.elem_type == TensorProto.FLOAT16:
            vi.type.tensor_type.elem_type = TensorProto.FLOAT
    # Cast nodes to FLOAT16 become no-ops -> retarget to FLOAT
    for node in g.node:
        if node.op_type == "Cast":
            for a in node.attribute:
                if a.name == "to" and a.i == TensorProto.FLOAT16:
                    a.i = TensorProto.FLOAT
    onnx.save(m, f"{OUT}/htdemucs_fp32.onnx", save_as_external_data=False)
    print(f"htdemucs fp32: converted {n16} fp16 initializers -> {os.path.getsize(OUT+'/htdemucs_fp32.onnx')/1e6:.1f} MB")
except Exception as e:
    print("fp32 conversion FAILED:", e)

# 2) Fixed static shapes for the small models (batch 1) so the CoreML EP can take them.
from onnxruntime.tools.onnx_model_utils import make_dim_param_fixed, make_input_shape_fixed, fix_output_shapes
for src, dst, inp, shape in [
    (f"{M}/nsfw_mnv2_140_f32.onnx", f"{OUT}/nsfw_f32_static.onnx", "input", [1,3,224,224]),
    (f"{M}/nsfw_mnv2_140_int8.onnx", f"{OUT}/nsfw_int8_static.onnx", "input", [1,3,224,224]),
    (f"{M}/genderage.onnx", f"{OUT}/genderage_static.onnx", "data", [1,3,96,96]),
]:
    try:
        m = onnx.load(src)
        make_input_shape_fixed(m.graph, inp, shape)
        fix_output_shapes(m)
        onnx.save(m, dst)
        print("fixed:", os.path.basename(dst), [ (v.name,[d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim]) for v in m.graph.input ],
              "->", [ (v.name,[d.dim_value or d.dim_param for d in v.type.tensor_type.shape.dim]) for v in m.graph.output ])
    except Exception as e:
        print("fix FAILED", src, type(e).__name__, e)
