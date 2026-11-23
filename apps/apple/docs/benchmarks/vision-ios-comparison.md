# مقارنة الوجوه والأجسام على معالج Apple

**2026-10-02 — اختبارات فعلية على Apple M3، وليست قياسات iPhone.**

الخيار العملي الأول لهذه الجولة هو **YOLO11n-seg بصيغة Core ML مع كاشف وجوه Apple Vision والتصنيف الحالي InsightFace**. يعطي YOLO11 صناديق وأقنعة منفصلة للأشخاص، وحقق ارتباط الوجه بالجسم بصورة أوضح من YOLO26n-seg في الحالات المراجعة. عند طلب تغبيش كامل الجسم، ينبغي استخدام صندوق جسم موسع كخيار محافظ؛ الأقنعة وحدها تركت أجزاء ظاهرة من الملابس أو الجلد.

هذه توصية أولية للموديل القابل للتجربة على iOS. لم يتوفر iPhone فعلي، ولم تُقَس الحرارة أو الذاكرة على الهاتف أو زمن تصدير الفيديو الكامل. لا تدّعي هذه الجولة اجتياز شرط تغطية 98% من الجسم أو استقرار الهوية عبر الفيديو.

## ما اختُبر

- الفيديوان المطلوبان: `-dQJ3djthDc` بطول 54.214 ثانية، و`rX6wXhLqOIQ` بطول 53.294 ثانية.
- 107 صور مشتركة، باستخراج `ffmpeg fps=1,scale=-2:640`، بدقة 360×640. أزمنة مثل 36.5 ثانية هي تسميات تقريبية لعينات الثانية، وليست قياسًا لتوقيت كل إطار أصلي.
- Vision: الوجه بإصدارَي الطلب 3 و4، مستطيلات البشر بإصدارَي 2 و3، أقنعة الأشخاص المنفصلة، والتجزئة الجماعية fast/balanced/accurate؛ مرة باختيار النظام ومرة بتعيين CPU لمرحلة `.main` فقط.
- YOLO11n-seg وYOLO26n-seg: PyTorch CPU كمرجع، ثم نفس الأوزان محولة إلى Core ML FP16؛ `CPU_ONLY` و`CPU_AND_GPU` و`ALL`.
- الإجمالي: 1,712 نتيجة Vision و856 نتيجة YOLO. لم ترمِ طلبات Vision أخطاء، واكتملت تشغيلات YOLO الأربعة لكلا الموديلين.
- فحص مرئي لعشرة إطارات مع 11 ظهورًا رئيسيًا للأشخاص، وإطار خالٍ من البشر. توجد أيضًا 28 نقطة داخل الجسم في ثلاث حالات صعبة. النقاط والصناديق مرجع تشخيصي محدود، وليست أقنعة حقيقة كاملة أو عينة عشوائية لقياس recall/AP.

الجهاز Apple M3 بذاكرة 24GB، macOS 27.0.1، build `26A434`. نُفِّذت القياسات بالتتابع في فترات اتفق عليها العاملون لتجنب تشغيل نماذج الصوت بالتوازي.

## السرعة المقاسة هنا

الجدول يعرض الوسيط بالمللي ثانية. زمن Vision يشمل الطلب على ملف الصورة؛ زمن YOLO في عمود الاستدلال هو مرحلة الاستدلال لدى المضيف. لذلك لا تتطابق حدود القياس بين العمودين. زمن إنشاء وحفظ الأقنعة يشمل PNG للمراجعة، ولا يمثل كلفة حفظ أقنعة التطبيق المثالية.

| الخيار | الطلب/الاستدلال | الطلب مع إنشاء وحفظ الأقنعة |
|---|---:|---:|
| Vision face revision 3، اختيار النظام | 6.90 | 6.91 |
| Vision face revision 4، اختيار النظام | 8.49 | 8.49 |
| Vision human rectangles revision 2 | 6.44 | 6.44 |
| Vision human rectangles revision 3 | 8.78 | 8.78 |
| Vision person instance masks | 112.86 | 132.08 |
| Vision semantic fast | 8.30 | 15.31 |
| Vision semantic balanced | 18.88 | 29.18 |
| Vision semantic accurate | 60.67 | 136.81 |
| YOLO11n-seg، Core ML CPU | 17.73 | 20.16 |
| YOLO11n-seg، Core ML CPU+GPU | 11.93 | 14.66 |
| YOLO11n-seg، Core ML ALL | 7.02 | 9.36 |
| YOLO26n-seg، Core ML CPU | 18.10 | 20.03 |
| YOLO26n-seg، Core ML CPU+GPU | 11.53 | 13.77 |
| YOLO26n-seg، Core ML ALL | 7.84 | 9.70 |

زمن التنبؤ الكامل قبل كتابة الأقنعة لدى مضيف Python: YOLO11 بلغ 19.46/13.92/8.67 ms، وYOLO26 بلغ 19.34/12.88/9.03 ms، حسب CPU/CPU+GPU/ALL. اختيار ALL سرّع YOLO11 بنحو 2.24× مقابل CPU في هذا المسار على M3. تتيح Core ML جدولة العمليات على CPU/GPU/Neural Engine، لكن السماح بها لا يثبت تنفيذ كل عملية على ANE؛ لم تُجمع آثار توزيع العمليات. [شرح Core ML من Ultralytics](https://docs.ultralytics.com/integrations/coreml/).

تعيين CPU في Vision كان لمرحلة `.main` فقط، وليس تعهدًا بتشغيل الطلب بكامله على CPU. يحدد API جهازًا لمرحلة معالجة بعينها، وتتحقق Vision من صلاحيته عند التنفيذ. [توثيق Apple للتعيين](https://developer.apple.com/documentation/vision/visionrequest/setcomputedevice%28_%3Afor%3A%29).

## ما ظهر في الفيديوين

| الخيار | مطابقة الظهور الرئيسي في 10 إطارات | نتيجة نقاط الجسم |
|---|---:|---:|
| Vision human revision 2 | 7/11 | 19/28 |
| Vision human revision 3 | 10/11 | 28/28 |
| Vision instance masks، ضم كل أقنعة الإطار | 10/11 | 22/28 |
| Vision semantic fast، قناع جماعي | لا يحدد أشخاصًا منفصلين | 28/28 |
| Vision semantic balanced، قناع جماعي | لا يحدد أشخاصًا منفصلين | 25/28 |
| Vision semantic accurate، قناع جماعي | لا يحدد أشخاصًا منفصلين | 26/28 |
| YOLO11n-seg، ALL | 10/11 | 26/28 |
| YOLO26n-seg، ALL | 10/11 | 26/28 |

أُجريت مطابقة الصناديق التشخيصية عند IoU≥0.3 على الصناديق التقريبية المراجعة. الخلفية في الإطارات الإيجابية غير معنونة بالكامل؛ لذلك لا تُعد كل زيادة في عدد الكشف خطأً. القناع الجماعي ليس كاشف أشخاص منفصلين، ولا تقارن نسبة نقاطه بنسب نجاح التمييز بين النساء والرجال. نجاح جميع النقاط لا يعني تغطية حدود الشعر والأصابع أو كل بكسلات الجسم.

الحالات المؤثرة في القرار:

1. **الأيدي القريبة والحلي، الفيديو الأول نحو 36.5 ثانية:** أقنعة Vision وYOLO تركت فراغات داخل الملابس أو الأصابع؛ صندوق الجسم يغطيها لكنه يغطي خلفية إضافية. YOLO26 أعاد صندوقين متداخلين لظهور واحد، فأصبح ارتباط الوجه بالجسم ملتبسًا.
2. **الظهر، الفيديو الثاني نحو 17.5 ثانية:** كُشف الجسم مع غياب الوجه. يجب استمرار قرار التصنيف السابق عبر تتبع الجسم داخل اللقطة. الكاشف وحده لا يحتفظ بهذا القرار.
3. **جزء جسم بلا وجه، نحو 28.5 ثانية:** Vision instance فصل أجزاء الجسم الواحد إلى أكثر من معرف. تطبيق قرار مختلف لكل معرف دون ربطه بصندوق الشخص قد يترك جزءًا ظاهرًا.
4. **الشخص داخل شاشة كاميرا صغيرة، نحو 8.5 ثانية:** كلا YOLO لم يكشف الظهور الصغير في هذه العينة. عدم اكتشاف الجسم ليس تصنيفًا «unknown»؛ يلزم التعامل معه في تقييم التغطية، ولا يكفي جعل المصنف يمتنع.
5. **ثلاثة مقاطع مركبة، نحو 52.5 ثانية:** Vision human revision 2 لم يكشف الأجسام، بينما revision 3 وكلا YOLO كشفوا المقاطع الثلاثة. Vision instance قسم بعض الوجه/الشعر/الجسم إلى تسميات مختلفة. معرف قناع الصورة لا يعني هوية شخص عبر الزمن.
6. **المباني فقط، نحو 36.5 ثانية:** لم ينتج كاشفا YOLO أشخاصًا في العينة السلبية. الإطارات الأخرى للمدينة تتضمن مشاة بعيدين، فلا تُصنَّف تلقائيًا كعينات سلبية.

قاعدة الارتباط المختبرة: يغطي صندوق الجسم ≥80% من صندوق الوجه، ويقع مركز الوجه ضمن الثلث العلوي للجسم، ويكون المرشح وحيدًا. في حالات المراجعة التي وجد فيها Vision الوجه، حقق YOLO11 ارتباطًا وحيدًا لـ8/8 وجوه، مقابل 6/8 لـYOLO26. هذا ليس تقييم تتبع أو برهانًا ضد تبديل الهوية عند التقاطع والاحتجاب.

Vision person instance محدود بأربعة أشخاص في وصف Apple؛ المشاهد المزدحمة قد تؤدي إلى دمج أشخاص أو إغفالهم. والتجزئة الجماعية تعيد قناعًا واحدًا لكل البشر، فلا تصلح وحدها لاختيار النساء أو الرجال في مشهد متداخل. [جلسة Apple عن التجزئة](https://developer.apple.com/videos/play/wwdc2023/111241/).

## تطابق التحويل والمسار المسرع

قورنت الصناديق والأقنعة المقابلة بنتائج PyTorch لنفس الصور، عند confidence=0.25. القيم التالية **اتفاق بين المرجع والتحويل، وليست دقة مع حقيقة معنونة**.

| الموديل والمسار | وسيط IoU الصناديق المطابقة | وسيط IoU الأقنعة المطابقة | إطارات تغير فيها عدد الكشف |
|---|---:|---:|---:|
| YOLO11 CPU | 0.9963 | 0.9962 | 4/107 |
| YOLO11 CPU+GPU | 0.9987 | 0.9996 | 1/107 |
| YOLO11 ALL | 0.9951 | 0.9889 | 4/107 |
| YOLO26 CPU | 0.9936 | 0.9957 | 3/107 |
| YOLO26 CPU+GPU | 0.9989 | 0.9995 | 0/107 |
| YOLO26 ALL | 0.9945 | 0.9921 | 7/107 |

مخرجات YOLO26 ALL في الإطار `-dQJ3djthDc/0019.png` تغيرت من كشفين متداخلين بثقة 0.877/0.865 في المرجع إلى كشف واحد بثقة 0.428؛ هذا ليس مجرد عبور صغير لعتبة 0.25. لا يثبت ذلك وحده فقد شخص حقيقي، لأن المصدر يعرض شخصًا واحدًا، لكنه يمنع وصف مسار ALL بأنه متطابق بلا تحفظ. تغيرات عدد YOLO11 ALL كانت في إطارات المشاة بالخلفية، مع بقاء المقدم الرئيسي في الحالات المراجعة.

**مسار المقارنة المحافظ: YOLO11 Core ML CPU+GPU. أسرع مسار مقاس: YOLO11 ALL، مرشح للاختبار على الهاتف بعد توسيع فحص التغطية والنتائج.** لا يوجد اجتياز مطلق للتطابق: بعض الكشف غير المطابق ما زال موجودًا، والنموذج لم يختبر على مجموعة أصغر الوجوه أو كل إطارات الفيديو.

## التطبيق على iOS

- YOLO11 Core ML يعطي أقنعة وصناديق منفصلة مع دعم أوسع للمشاهد من حد أربعة أقنعة Vision. يستخدم نموذج الاختبار دخل RGB بحجم 640×640 وletterbox بلون 114 ثم /255؛ يجب المحافظة على معالجة الصور وتصحيح الصناديق نفسها، لا استبدالها بتعبئة سوداء دون فحص تطابق.
- الصيغة المختبرة MLProgram FP16، مع `half=True,nms=False,dynamic=False`. يظل YOLO11 محتاجًا NMS وفك مخرجات `[1,116,8400]` مع prototype `[1,32,160,160]`. مخرجات YOLO26 المختبرة `[1,300,38]` مع prototype. استعمال نفس مفسر المخرجات دون التمييز بينهما خطأ.
- يبقى `DetectFaceRectanglesRequest()` الافتراضي الحالي: revision 4 على OS27 وrevision 3 على الأنظمة الأقدم في SDK المستخدم. لا يلزم فرض إصدار جديد لاستعمال الاختيار الافتراضي. أما اختيار face revision 4 أوhuman revision 3 صراحة فيحتاج فحص التوفر والرجوع للإصدارات الأقدم، لأن التطبيق يدعم iOS 18. ملف Swift التجريبي يعمل على المضيف OS27 ولا يغير هدف نشر التطبيق.
- خيار everyone يتجاوز المصنف، وخيارا women/men يعتمدان على evidence من الوجه المرتبط بالشخص. عند عدم وجود وجه واضح أو وجود ارتباط ملتبس، تظل النتيجة unknown ويُطبق خيار التغطية المحافظ.
- يظل القرار مع **مسار الشخص داخل اللقطة** عند اختفاء الوجه؛ لا يُنقل بناءً على إعادة استخدام رقم track. ينبغي إنهاء أو إعادة ضبط الارتباط عند انتقال المشهد.
- تغبيش الجسم الكامل المحافظ يستخدم صندوقًا موسعًا أو تغطية أكبر عند غياب قناع موثوق. الأقنعة الدقيقة تبقى خيار جودة بصري يحتاج اختبار الحدود والتتبع. تحليل 1 fps هنا لا يثبت أن الاستيفاء سيغطي حركة الجسم على 30 fps.

## المصنف: InsightFace مقابل MiVOLO v2

أوزان MiVOLO v2 الرسمية متوفرة وتحوي رأس الجنس، وليست مقتصرة على العمر؛ المرجع HF ثابت على `53393526c220e34cdd7b722b36d22b6f9e5f4241`، والكود على `37475e3f8818b5f22448003feec3e64b01bfb188`. يستخدم دخل وجه+جسم 384×384×6، 28.8 مليون معامل، ملفًا بحجم نحو 109 MiB. [بطاقة الموديل الرسمية](https://huggingface.co/iitolstykh/mivolo_v2).

اختُبرت 17 قصاصة واضحة يبلغ حجم الوجه فيها ≥80 بكسل، باستخدام هندسة قص InsightFace الحالية ومدخل RGB PNG؛ تحويل YUV الخاص بالتطبيق غير داخل هذه المقارنة. صنف InsightFace القصاصات الـ17 كأنثى؛ صنف MiVOLO 16 وامتنع في واحدة بسبب عدم وجود ارتباط وحيد بين الوجه والجسم. هذا اتفاق مع مراجعة المظهر للمقدمتين، وليس حقيقة عن هويتهما أو دقة عامة للموديل.

وسيط InsightFace ONNX CPU كان 0.60 ms مع قص الصورة. وسيط MiVOLO FP32 بعد استبعاد أول ثلاثة سجلات كان 80.42 ms للاستدلال وحده. عند طلب MPS، سجل PyTorch أن `aten::col2im` عاد إلى CPU؛ لم يكن التشغيل كاملًا على GPU. حدود القياس مختلفة، لكن هذه الجولة لا تقدم فائدة تبرر استبدال المصنف الصغير.

**فشلت محاولة التحويل القياسية إلى Core ML FP16 بهدف iOS 18** في coremltools 9.0 برسالة `Only supports stride >= kernel_size for col2im (fold)`. لم تُعدَّل بنية الموديل لتجاوزها. لذلك **يبقى InsightFace هو الاختيار الحالي**، وMiVOLO مرشح بحثي يحتاج تحويلًا صحيحًا ومجموعة تقييم متنوعة قبل اعتماده. [نتائج القصاصات](results/vision-gender-mps.jsonl)، [سجل فشل التحويل](results/vision-mivolo-export.json).

الفيديوان يعرضان مقدمَتين رئيسيتين فقط، فلا يمكن قياس دقة الرجال والنساء أو الأطفال أو الوجوه المغطاة بهذه المجموعة. لا يُستبدل InsightFace بسبب أن كاشف جسم آخر نجح؛ المصنف يحتاج دليلًا مستقلًا على فائدته وتطابق تشغيله على iOS.

## إعادة التشغيل والآثار

تثبيت حزم المقارنة في بيئة منفصلة:

```sh
uv venv --python 3.12 build.noindex/vision-modelbench
uv pip install --python build.noindex/vision-modelbench/bin/python -r scripts/modelbench/vision-requirements.txt
swiftc -O -parse-as-library scripts/modelbench/vision_native.swift -o build.noindex/vision-modelbench/vision_native
build.noindex/vision-modelbench/vision_native --self-check
build.noindex/vision-modelbench/bin/python scripts/modelbench/vision_yolo.py --self-check
build.noindex/vision-modelbench/bin/python scripts/modelbench/vision_gender.py --self-check
```

تجهيز صور الاختبار والأوزان، بعد تنزيل الفيديوين الأصليين وفق manifest العام. استعمل Python البيئة السابقة للأوامر اللاحقة، أو فعّلها في هذه الجلسة:

```sh
source build.noindex/vision-modelbench/bin/activate
NAQI_VISION_ASSETS=/absolute/path/to/qa-assets/modelbench/vision
mkdir -p "$NAQI_VISION_ASSETS/models" "$NAQI_VISION_ASSETS/frames/-dQJ3djthDc" "$NAQI_VISION_ASSETS/frames/rX6wXhLqOIQ"
curl -fL https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo11n-seg.pt -o "$NAQI_VISION_ASSETS/models/yolo11n-seg.pt"
curl -fL https://github.com/ultralytics/assets/releases/download/v8.4.0/yolo26n-seg.pt -o "$NAQI_VISION_ASSETS/models/yolo26n-seg.pt"
ffmpeg -hide_banner -loglevel error -i "$NAQI_VISION_ASSETS/../-dQJ3djthDc.mp4" -vf 'fps=1,scale=-2:640' "$NAQI_VISION_ASSETS/frames/-dQJ3djthDc/%04d.png"
ffmpeg -hide_banner -loglevel error -i "$NAQI_VISION_ASSETS/../rX6wXhLqOIQ.mp4" -vf 'fps=1,scale=-2:640' "$NAQI_VISION_ASSETS/frames/rX6wXhLqOIQ/%04d.png"
build.noindex/vision-modelbench/vision_native "$NAQI_VISION_ASSETS/frames" "$NAQI_VISION_ASSETS/native-default" default
build.noindex/vision-modelbench/vision_native "$NAQI_VISION_ASSETS/frames" "$NAQI_VISION_ASSETS/native-cpu" cpu
```

أعد استخدام الفيديوين ذوي hash المطابق للـmanifest؛ قد يتغير ترميز YouTube عند تنزيل جديد. صورة الاختبار ذات hash واحد تُستعمل لكل خيار، فلا تُقارن أرقام مجموعة أعيد ترميزها بأرقام هذه الجولة.

الأوزان الأصلية من [إصدار Ultralytics assets v8.4.0](https://github.com/ultralytics/assets/releases/tag/v8.4.0). يوثق [vision-summary.json](results/vision-summary.json) hash للأوزان ولكل ملف في حزمة Core ML، إعدادات التحويل، نتائج الاتفاق، والقياسات. تسجل الأوزان ترخيص AGPL-3.0؛ لم تكن الرخصة معيار استبعاد في هذه الجولة.

```sh
python scripts/modelbench/vision_yolo.py export --assets /absolute/path/to/qa-assets/modelbench/vision
python scripts/modelbench/vision_yolo.py run --assets /absolute/path/to/qa-assets/modelbench/vision --compute ALL
python scripts/modelbench/vision_yolo.py run --assets /absolute/path/to/qa-assets/modelbench/vision --compute CPU_AND_GPU
python scripts/modelbench/vision_yolo.py run --assets /absolute/path/to/qa-assets/modelbench/vision --compute CPU_ONLY
python scripts/modelbench/vision_yolo.py run --assets /absolute/path/to/qa-assets/modelbench/vision --compute reference
python scripts/modelbench/vision_review.py --assets /absolute/path/to/qa-assets/modelbench/vision
python scripts/modelbench/vision_results.py --assets /absolute/path/to/qa-assets/modelbench/vision --output docs/benchmarks/results/vision-summary.json
```

إعادة فحص MiVOLO، دون تثبيت أو تنفيذ كود Hugging Face ديناميكيًا:

```sh
git clone https://github.com/WildChlamydia/MiVOLO.git build.noindex/vision-modelbench/MiVOLO
git -C build.noindex/vision-modelbench/MiVOLO checkout --detach 37475e3f8818b5f22448003feec3e64b01bfb188
mkdir -p "$NAQI_VISION_ASSETS/models/mivolo_v2"
curl -fL https://huggingface.co/iitolstykh/mivolo_v2/resolve/53393526c220e34cdd7b722b36d22b6f9e5f4241/model.safetensors -o "$NAQI_VISION_ASSETS/models/mivolo_v2/model.safetensors"
python scripts/modelbench/vision_gender.py --assets "$NAQI_VISION_ASSETS" --mivolo-source build.noindex/vision-modelbench/MiVOLO --genderage /path/to/genderage_static.onnx --device mps
python scripts/modelbench/vision_gender.py --assets "$NAQI_VISION_ASSETS" --mivolo-source build.noindex/vision-modelbench/MiVOLO --genderage /path/to/genderage_static.onnx --export
```

الأمر الأخير يعيد تجربة التحويل التي فشلت بهذه النسخ المثبتة؛ فشله لا يمنع تشغيل بقية المقارنة.

فحص إضافي بُني على قناع float ثابت 0.5 وأثبت أن مسار Core Image الافتراضي المستعمل ينتج قيمة PNG=128؛ عتبة مراجعة PNG لا تتعرض لانزياح إلى sRGB=188 في هذا المسار. هذا فحص لتمثيل القناع، وليس دليلًا على صحة النموذج.

الصور والوزن ومئات الأقنعة خارج git، تحت `qa-assets/modelbench/vision`. السجلات والـhashes ومرجع المراجعة محفوظة في الفرع. راجع:

- الأيدي والملابس: `qa-assets/modelbench/vision/review/-dQJ3djthDc_0037_compare.jpg`.
- الجسم والوجه الغائب من الخلف: `qa-assets/modelbench/vision/review/rX6wXhLqOIQ_0018_compare.jpg`.
- الثلاثة مقاطع المركبة: `qa-assets/modelbench/vision/review/rX6wXhLqOIQ_0053_compare.jpg`.
- [الصناديق والنقاط المراجعة](vision-review-cases.json)، [المطابقة](results/vision-presence-results.json)، [نقاط التغطية](results/vision-probe-results.json).
