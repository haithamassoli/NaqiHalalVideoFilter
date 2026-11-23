import onnx, sys, collections
from onnx import TensorProto
p = sys.argv[1]
m = onnx.load(p, load_external_data=False)
g = m.graph
print("== ", p)
print("ir_version", m.ir_version, "opsets", [(o.domain or 'ai.onnx', o.version) for o in m.opset_import])
print("producer", m.producer_name, m.producer_version)
dt = collections.Counter(TensorProto.DataType.Name(i.data_type) for i in g.initializer)
print("initializer dtypes:", dict(dt))
ops = collections.Counter(n.op_type for n in g.node)
print("node count:", len(g.node))
print("ops:", dict(sorted(ops.items(), key=lambda kv: -kv[1])))
def tinfo(v):
    t = v.type.tensor_type
    return (v.name, TensorProto.DataType.Name(t.elem_type),
            [d.dim_value if d.HasField('dim_value') else (d.dim_param or '?') for d in t.shape.dim])
print("inputs:", [tinfo(v) for v in g.input])
print("outputs:", [tinfo(v) for v in g.output])
# conv adjacency check (repro of perf-plan-v3 s4)
prod = {o: n for n in g.node for o in n.output}
convs = [n for n in g.node if n.op_type == "Conv"]
adj = sum(1 for n in convs if n.input and prod.get(n.input[0], None) is not None and prod[n.input[0]].op_type == "Conv")
print(f"Conv: {len(convs)}  ConvTranspose: {ops.get('ConvTranspose',0)}  Pool-ish: {sum(v for k,v in ops.items() if 'Pool' in k)}  Conv-fed-by-Conv: {adj}")
