"""Compare the existing genderage crop contract with pinned official MiVOLO v2.

This small, two-presenter corpus cannot establish male/female accuracy in general.
Face/body inputs come from measured detectors; ambiguous associations abstain.
"""
import argparse
import hashlib
import json
import sys
import time
from pathlib import Path

import cv2
import numpy as np

from vision_yolo import associate_face


def insight_crop(image, box):
    h, w = image.shape[:2]
    left, top, right, bottom = np.asarray(box) * [w,h,w,h]
    half = max(right-left,bottom-top)*0.75
    xs = np.clip(((left+right)/2-half+np.arange(96)*(2*half/96)).astype(int),0,w-1)
    ys = np.clip(((top+bottom)/2-half+np.arange(96)*(2*half/96)).astype(int),0,h-1)
    # PNG RGB screening follows the crop geometry/range, not the app's YUV color conversion.
    return image[ys[:,None],xs[None,:],::-1].transpose(2,0,1)[None].astype(np.float32)


def crop(image, box):
    h,w = image.shape[:2]
    x1,y1,x2,y2 = np.round(np.asarray(box)*[w,h,w,h]).astype(int)
    return image[max(0,y1):min(h,y2),max(0,x1):min(w,x2)]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--assets",type=Path)
    parser.add_argument("--mivolo-source",type=Path)
    parser.add_argument("--genderage",type=Path)
    parser.add_argument("--device",choices=["cpu","mps"],default="mps")
    parser.add_argument("--export",action="store_true")
    parser.add_argument("--self-check",action="store_true")
    args=parser.parse_args()
    if args.self_check:
        image=np.full((10,20,3),[1,2,3],np.uint8)
        result=insight_crop(image,[-0.1,-0.1,0.1,0.1])
        assert result.shape==(1,3,96,96)
        assert np.array_equal(result[0,:,0,0],[3,2,1])
        assert np.array_equal(result[0,:,-1,-1],[3,2,1])
        print('InsightFace edge-clamped RGB crop check passed')
        return
    if args.assets is None or args.mivolo_source is None or args.genderage is None:
        parser.error('--assets, --mivolo-source and --genderage are required')
    sys.path.insert(0,str(args.mivolo_source))
    import torch
    from safetensors.torch import load_file
    from mivolo.model.create_timm_model import create_model
    from mivolo.data.misc import prepare_classification_images
    import onnxruntime as ort

    torch.set_num_threads(1)
    checkpoint=args.assets/"models/mivolo_v2/model.safetensors"
    checkpoint_hash=hashlib.sha256(checkpoint.read_bytes()).hexdigest()
    genderage_hash=hashlib.sha256(args.genderage.read_bytes()).hexdigest()
    model=create_model("mivolo_d1_384",num_classes=3,in_chans=6,pretrained=False).eval()
    model.load_state_dict({k.removeprefix("mivolo.model."):v for k,v in load_file(checkpoint).items()},strict=True)
    if args.export:
        import coremltools as ct
        example=torch.zeros(1,6,384,384)
        traced=torch.jit.trace(model,example)
        converted=ct.convert(traced,inputs=[ct.TensorType(name="face_body",shape=example.shape)],
                             convert_to="mlprogram",minimum_deployment_target=ct.target.iOS18,
                             compute_precision=ct.precision.FLOAT16)
        converted.save(str(args.assets/"models/mivolo_v2/mivolo_v2.mlpackage"))
        print("MiVOLO v2 Core ML export completed")
        return
    model=model.to(args.device)
    session=ort.InferenceSession(str(args.genderage),providers=["CPUExecutionProvider"])
    records={}
    for line in (args.assets/"native-default/native-default.jsonl").read_text().splitlines():
        row=json.loads(line)
        records[row['video'],row['frame'],row['candidate']]=row
    output=args.assets/f"gender-{args.device}.jsonl"
    with output.open("w") as log:
        for video in ("-dQJ3djthDc","rX6wXhLqOIQ"):
            for frame in sorted((args.assets/"frames"/video).glob("*.png"))[::4]:
                image=cv2.imread(str(frame))
                faces=records[video,frame.name,"face-r4"]['boxes']
                bodies=records[video,frame.name,"human-r3"]['boxes']
                for face in faces:
                    # Keep the clear, large presenter faces. Background/camera-display crops remain unscored.
                    h,w=image.shape[:2]
                    face_px=max((face[2]-face[0])*w,(face[3]-face[1])*h)
                    if face_px < 80:
                        continue
                    body_id=associate_face(face,bodies)
                    started=time.perf_counter()
                    logits=session.run(None,{"data":insight_crop(image,face)})[0][0]
                    diff=float(logits[1]-logits[0]); male=1/(1+np.exp(-diff))
                    insight_ms=(time.perf_counter()-started)*1000
                    row=dict(video=video,frame=frame.name,face=face,body_id=body_id,
                             genderage_male_probability=float(male),genderage_ms=insight_ms,
                             genderage_vote="unknown" if max(male,1-male)<.6 else "male" if male>=.5 else "female",
                             genderage_sha256=genderage_hash,
                             mivolo_sha256=checkpoint_hash,
                             mivolo_runtime="PyTorch",mivolo_device=args.device,
                             manual_label="female-presenting-presenter",label_scope="appearance review; two distinct presenters; not self-reported identity")
                    if body_id is None:
                        row['mivolo_vote']='unknown'
                        row['reason']='no unique face-to-body association'
                    else:
                        faces_input=prepare_classification_images([crop(image,face)],384)
                        bodies_input=prepare_classification_images([crop(image,bodies[body_id])],384)
                        tensor=torch.cat([faces_input,bodies_input],dim=1).to(args.device)
                        if args.device=='mps':torch.mps.synchronize()
                        started=time.perf_counter()
                        with torch.inference_mode():
                            pred=model(tensor)[0,:2].softmax(0)
                        if args.device=='mps':torch.mps.synchronize()
                        row['mivolo_ms']=(time.perf_counter()-started)*1000
                        prob=pred.detach().cpu().numpy()
                        row['mivolo_probabilities']=prob.tolist()
                        row['mivolo_vote']='unknown' if float(prob.max())<.6 else ['male','female'][int(prob.argmax())]
                    log.write(json.dumps(row,sort_keys=True)+'\n');log.flush()
    print('Gender crop screening:',output)


if __name__=="__main__":
    main()
