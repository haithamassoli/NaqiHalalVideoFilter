<p align="center">
  <img src="../../branding/naqi-icon-1024.png" width="128" alt="أيقونة نقي: مثلث تشغيل أخضر فوق كأس حرف النون">
</p>

<h1 align="center">نقي لويندوز</h1>

<p align="center">
  تطبيق ويندوز يفلتر ملف الفيديو <b>على حاسوبك</b>: يزيل الموسيقى من الصوت ويحجب الوجوه في الصورة. والملف الأصلي لا يُمَسّ.
</p>

<p align="center">
  <a href="../../LICENSE"><img src="https://img.shields.io/badge/licence-GPL--3.0--or--later-blue" alt="الرخصة: GPL-3.0-or-later"></a>
  <img src="https://img.shields.io/badge/status-beta%2C%20in%20development-orange" alt="الحالة: تجريبي قيد التطوير">
  <img src="https://img.shields.io/badge/Windows-10%2F11%20x64-0078d4" alt="ويندوز 10 أو 11، x64">
</p>

<p align="center">
  <a href="README.md">English</a> · <b>العربية</b>
</p>

<div dir="rtl">

هذا تطبيق ويندوز ضمن مستودع نقي الموحّد. ما تفعله التطبيقات، وبيان الخصوصية، والمنصّات الأخرى
موضّحة في [ملف README الرئيسي](../../README.ar.md). وهو منقول عن تطبيق أندرويد
([`../android`](../android)) الذي يبقى المرجع: منطق القرار (قائمة الحجب EDL، وبوابة المشاهد غير
اللائقة، وقواعد تتبّع الوجوه، وتقطيع إزالة الموسيقى) هو نفسه بلغة Kotlin، مشترك عبر
[`core/`](../../core).

**الحالة: تجريبي قيد التطوير.** لا يوجد إصدار موقّع بعد. ابنِه من المصدر، أو خذ ملف MSI أو الملف
المضغوط المحمول غير الموقّعَين من إصدار تجريبي `windows-v…` في صفحة إصدارات المستودع، أو من مخرجات
[سير عمل ويندوز](../../.github/workflows/windows.yml) على GitHub Actions.

## ما يعمل الآن

- **المدخل:** ملف فيديو على الحاسوب (MP4 وMOV وM4V وMKV وWebM وAVI وTS وغيرها مما يقرؤه FFmpeg).
  اختره من نافذة الملفات أو اسحبه وأفلته على النافذة.
- **المخرج:** ملف MP4 بجانب الأصل (`<name>-naqi.mp4`) أو حيث تختار. ولا يكتب فوق الأصل أبدًا. يُكتب
  أولًا في ملف مؤقت ولا يُعاد تسميته إلا بعد اكتماله، فلا يبقى شيء إن فشلت المهمة أو أُلغيت.
- **إزالة الموسيقى:** بوابة YAMNet (للفيديوهات التي تبلغ 10 دقائق فأكثر، كما في أندرويد) ونموذج
  htdemucs على المعالج. أبقِ `vocals` (الأصوات)، أو `vocals + other` للإبقاء على المؤثرات الصوتية مع
  تسرّب شيء من الموسيقى.
- **حجب الوجوه:** تختار من يُحجب: الجميع، أو النساء، أو الرجال. يكتشف نموذج YuNet الوجوه ويتتبّعها
  عبر الإطارات، ويحصل كل مسار على 5 أصوات كحدّ أقصى من نموذج `genderage` من InsightFace. وعند
  التعادل أو الامتناع أو صغر الوجه عن التصنيف، يُحجب الوجه.
- **الأسلوب:** قوة التمويه، أو تعبئة بلون ثابت بدل التمويه (5 ألوان)، والتدرّج الرمادي، ووضع الشاشة
  كاملة (تُغطّى الصورة كلها ما دام وجه محجوب ظاهرًا).
- **بوابة المشاهد غير اللائقة:** تغطّي الإطار كاملًا ما دامت تعمل، بصرامة من 0 إلى 100.
- **التوقيت:** يحتفظ كل إطار بتوقيته الأصلي، ويحتفظ الصوت بإزاحته، وتُنقل بيانات الدوران كما هي.
  وإن لم يتغيّر شيء في الصورة، يُنسخ الفيديو دون إعادة ترميز.
- **الواجهة** بالعربية والإنجليزية (من اليمين إلى اليسار في العربية) حسب لغة النظام. تعمل المهام
  واحدة تلو الأخرى مع شريط تقدّم وتقدير للوقت وزر إلغاء.
- **سطر الأوامر** للاستخدام في السكربتات؛ انظر أدناه.

## ما لم يأتِ بعد

هذه مؤجّلة لا منسيّة: تغطية الجسم والجسم بدقة، والتنزيل بالرابط، وبطاقة النهاية، وإعدادات النشر
والتقسيم، والاستدلال على كرت الشاشة (DirectML)، وويندوز ARM64، والتحديث التلقائي، وتوقيع الشيفرة،
وحزم MSIX. ولا تُحفظ نقاط استئناف للفيديوهات الطويلة، فالمهمة المقطوعة تبدأ من أولها.

## الفروق عن أندرويد

| | أندرويد | ويندوز |
| --- | --- | --- |
| كاشف الوجوه | ML Kit (بمعرّفات التتبّع الخاصة به) | YuNet (من opencv_zoo، رخصة MIT)، يُتتبّع بتداخل المربعات |
| الاستدلال | ONNX Runtime على الهاتف | ONNX Runtime 1.27.0 على المعالج |
| الفيديو | Media3 / MediaCodec | FFmpeg (JavaCPP، نسخة GPL)، وlibx264 بقيمة CRF 18، وAAC بمعدل 192 kbps |
| التغطية | الوجه، الجسم، الجسم بدقة، الشاشة | الوجه، الشاشة |

## البناء والتشغيل

تحتاج JDK 21 (يكفي JBR المرفق مع Android Studio) والنماذج. كل ما يلي يُنفَّذ من `apps/windows`، على
ويندوز وعلى macOS (وعليه يُختبر يوميًا).

</div>

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS; any JDK 21

./gradlew :app:stageModels         # the ONNX models into apps/windows/models (gitignored), sha256-checked
./gradlew :app:run                 # the window
./gradlew :core:test :app:test     # the tests
./gradlew :app:packagePortableZip  # zipped app image, any OS
./gradlew :app:packageMsi          # the installer, Windows only
```

<div dir="rtl">

على ويندوز، نفّذها من Git Bash (`./gradlew`) أو من موجّه الأوامر (`gradlew.bat`). تحتاج مهمة
`stageModels` إلى Git for Windows، لأنها تشغّل `scripts/stage-models.sh` بواسطة bash المرفق معه.

**النماذج.** تنسخ `stageModels` أربعة نماذج من مجلد أندرويد `../android/app/src/main/assets/models`
وتنزّل YuNet. وإن لم تكن نماذج أندرويد هناك، تشغّل `fetch-models.sh` الخاص بأندرويد، وهو يحتاج إعداد
Python الموضّح في [`apps/android/README.ar.md`](../android/README.ar.md). والطريق الأسرع، وهو ما يستخدمه
CI، أخذها من ملف APK منشور:

</div>

```sh
curl -fLo /tmp/naqi.apk https://github.com/haithamassoli/NaqiHalalVideoFilter/releases/download/v1.9.0/naqi-1.9.0.apk
(cd ../android/app/src/main && jar xf /tmp/naqi.apk assets/models/genderage.onnx \
  assets/models/htdemucs_s26_f16.onnx assets/models/nsfw_mnv2_140_int8.onnx assets/models/yamnet.onnx)
./gradlew :app:stageModels
```

<div dir="rtl">

الاختبارات التي تحتاج نموذجًا تُتخطّى إن غاب. ومع النماذج، يشغّل `PipelineTest` مهامّ كاملة من أولها
إلى آخرها على مقطع يولّده بنفسه.

## سطر الأوامر

البرنامج نفسه ينفّذ مهمة واحدة دون نافذة إذا أُعطي وسائط:

</div>

```
Naqi --input <video> --output <file.mp4> [--music vocals|vocals+other]
     [--faces everyone|women|men] [--style blur[:0-100]|fill:<gray|black|white|navy|green>|gray|whole[,…]]
     [--nsfw 0-100]
```

<div dir="rtl">

يُكتب التقدّم في المخرج القياسي على شكل أسطر `<STAGE> <percent>`، ثم `DONE <path>`. رمز الخروج 0 عند
الانتهاء، و1 إن فشلت المهمة، و2 إن كانت الوسائط خاطئة. ويُلغي Ctrl+C المهمة وينظّف ما خلّفته. ومن
نسخة المصدر: `./gradlew :app:run --args="--input in.mp4 --output out.mp4 --faces women"`. أما
`Naqi.exe` المثبّت فبرنامج بواجهة رسومية، فلا يظهر مخرجه على ويندوز إلا عند تحويله إلى ملف
(`Naqi.exe … > log.txt`).

## تثبيت النسخة التجريبية

يُثبَّت ملف MSI للمستخدم الحالي دون طلب صلاحيات المسؤول. والنسخ **غير موقّعة**، لذا يحذّر Windows
SmartScreen من أن الناشر غير معروف. اختر **More info** (مزيد من المعلومات)، ثم **Run anyway** (تشغيل
على أي حال). ولا تفعل ذلك إلا مع ملف نزّلته من GitHub Actions أو إصدارات هذا المستودع نفسه. أما
الملف المضغوط المحمول فلا يحتاج تثبيتًا: فكّ ضغطه وشغّل `Naqi.exe`.

## النشر

لا تُنشر إصدارات ويندوز إلا عبر [سير عمل Release Windows](../../.github/workflows/release-windows.yml)،
ولا تُنشر يدويًا بالأمر `gh release create` أبدًا. ارفع `appVersion` في `app/build.gradle.kts` على
`main`، ثم شغّل سير العمل من `main`: `gh workflow run release-windows.yml -f version=X.Y.Z` (أو
Actions > Release Windows > Run workflow). يتوقف قبل البناء إن كان إصدار إضافي سيدفع أحدث إصدار
أندرويد خارج أحدث 10 إصدارات، ثم يبني ويختبر على ويندوز، وينشر `windows-vX.Y.Z` إصدارًا تجريبيًا غير
معلَّم بـ Latest. ويحمل الإصدار ملف MSI والملف المضغوط المحمول ومصدر نسخة FFmpeg المرخّصة بـ GPL
المضمّنة (FFmpeg وx264 وx265 وسكربتات بناء JavaCPP Presets، مثبّتة بإصداراتها في
[`gpl-sources.sh`](../../.github/scripts/gpl-sources.sh)، وإليها يشير `NOTICE`). وقواعد ذلك في
[`docs/RELEASING.md`](../../docs/RELEASING.md).

## الأداء

قيس على MacBook Air بمعالج Apple M3 عبر سطر الأوامر، بمقطع مدته 30 ثانية بدقة 720p. ويشمل كل تشغيل
وقت بدء JVM.

| المهمة | الوقت |
| --- | --- |
| إزالة الموسيقى (`vocals`) | 13 ثانية |
| إزالة الموسيقى (`vocals + other`) | 22 ثانية |
| حجب الوجوه (تمويه أو تعبئة أو تدرّج رمادي) | 10–16 ثانية |
| وضع الشاشة كاملة | 20 ثانية |
| الموسيقى والوجوه معًا | 42 ثانية |

ثمة كلفتان معروفتان: نسخة x264 لمعالجات macOS arm64 تعمل دون SIMD، فإعادة الترميز على ماك أبطأ مما
ستكون عليه على ويندوز x64، وفكّ الترميز والاستدلال والترميز تجري واحدة بعد الأخرى لا متداخلة كما في
أندرويد. ولم تُقَس الأوقات على ويندوز بعد.

## ثغرات معروفة

- لم يُشغَّل بعد على جهاز ويندوز حقيقي؛ يبنيه CI ويختبره على `windows-latest`.
- عتبات YuNet (الدرجة 0.6، والتداخل 0.3، والفجوة 500 ms) مختارة لا معايَرة مقابل نتائج ML Kit في
  أندرويد.
- تصنّف بوابة المشاهد غير اللائقة إطار التحليل بحجم 640 بكسل، بينما يصنّف أندرويد إطار المصدر كاملًا.
  ويلزم التحقق من حساسيتها مقابل أندرويد على مقاطع حقيقية.
- الأبعاد الفردية للإطار تفشل في الترميز، ومصادر HDR بعمق 10 بت تُكتب بعمق 8 بت دون تحويل نغمي.
- لا يحمل المخرج خيار `faststart`، ففهرس MP4 في آخر الملف.
- ملفات خط Thmanyah Sans هي نفسها التي يحملها تطبيق أندرويد، ورخصتها لم تُسجَّل في `NOTICE` بعد.

## الرخص

التطبيق مرخّص بـ GPL-3.0-or-later، كبقية المستودع خارج `apps/apple`. ويضمّ:

| المكوّن | الرخصة |
| --- | --- |
| كاشف الوجوه YuNet (`face_detection_yunet_2023mar.onnx`، من opencv_zoo) | MIT |
| htdemucs (من Demucs، مُعاد تصديره إلى ONNX) | MIT |
| YAMNet | Apache-2.0 |
| `genderage` من InsightFace | الشيفرة MIT؛ والأوزان لغير الأغراض التجارية، وتنازل المالك عن هذا القيد (انظر [`NOTICE`](../../NOTICE) في الجذر) |
| نموذج المشاهد غير اللائقة (`nsfw_model` من GantMan، بدقة INT8) | **NOASSERTION في المصدر، ولم تُحسم بعد**: اقرأ [`NOTICE`](../../NOTICE) في الجذر قبل إعادة التوزيع |
| FFmpeg مع libx264 (نسخة GPL من JavaCPP) | GPL-2.0-or-later |
| JavaCPP وإعدادات FFmpeg المسبقة الخاصة به | Apache-2.0 |
| ONNX Runtime | MIT |
| Compose Multiplatform وKotlin وkotlinx.coroutines | Apache-2.0 |

تعرض شاشة «حول» الإشعارات نفسها.

</div>
