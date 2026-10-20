# المعاون · Muawin

واجهة عربية لشاشة الراكب في BYD Leopard 5 (方程豹 豹5)، مستوحاة من [تلفزيونك · UrTV](https://github.com/tshi-dev/UrTV).

الساعة والتاريخ الهجري، الصلاة القادمة، الطقس، حديث اليوم، المباريات (اختياري)، مشغّل الوسائط، والتطبيقات المفضلة، فوق صور طبيعة سعودية تتغير كل نصف ساعة.

## التحميل

كل دفعة على فرع `main` تبني APK تلقائياً في GitHub Actions وتنشره في صفحة **Releases** باسم `muawin.apk`.

## التثبيت على السيارة (ADB)

```bash
adb connect <IP-السيارة>:5555
adb install -r muawin.apk

# عرض الوسائط (مرة واحدة)
adb shell cmd notification allow_listener ae.khalid.muawin/ae.khalid.muawin.MediaListener

# اعرف رقم شاشة الراكب
adb shell dumpsys display | grep -E "mDisplayId|mBaseDisplayInfo"

# افتح المعاون على شاشة الراكب (غيّر 2 لرقم شاشتك)
adb shell am start -n ae.khalid.muawin/.MainActivity --display 2
```

أو افتحه من الشاشة الرئيسية، اضغط مطولاً على الساعة، ومن «شاشة العرض» اختر شاشة الراكب.

## الاستخدام

| الحركة | النتيجة |
|---|---|
| ضغطة على تطبيق في الشريط | يفتح على نفس الشاشة |
| «كل التطبيقات» | قائمة كل التطبيقات |
| ضغطة مطولة على تطبيق في القائمة | إضافة للمفضلة أو إزالة (حتى ٦) |
| ضغطة مطولة على الساعة | الإعدادات: المدينة، طريقة الصلاة، الفريق، شاشة العرض |

## الإعدادات

- **المدينة:** الاسم وخط العرض والطول (الافتراضي أبوظبي). طريقة حساب الصلاة الافتراضية «منطقة الخليج».
- **المباريات:** رقم الفريق من [TheSportsDB](https://www.thesportsdb.com). فارغ = مخفية.

## البيانات

مجانية وبدون مفاتيح: [Aladhan](https://aladhan.com) للصلاة، [Open-Meteo](https://open-meteo.com) للطقس، [TheSportsDB](https://www.thesportsdb.com) للمباريات. التاريخ الهجري (أم القرى) يُحسب على الجهاز. كل شيء يتحمّل انقطاع الإنترنت ويعرض آخر بيانات محفوظة.

## خارطة الطريق

- [x] المرحلة ١: الواجهة والبيانات الحية
- [x] المرحلة ٢: غلاف أندرويد (التطبيقات، الوسائط، نقل الشاشة)
- [ ] المرحلة ٣: معلومات السيارة (البطارية، المدى، الحرارة، الإطارات) عبر ADB المحلي
- [ ] المرحلة ٤: تشغيل تلقائي على شاشة الراكب مع كل تشغيل للسيارة

## للمطورين

```
app/src/main/assets/web/   الواجهة (HTML/CSS/JS بدون framework) — افتح index.html في المتصفح للمعاينة
app/src/main/java/...      Bridge.java يكشف للصفحة: apps, icon, launch, displays, moveTo, media, mediaCmd
.github/workflows/         بناء APK ونشره
```

بناء محلي: `gradle assembleRelease` (يحتاج Android SDK و JDK 17).

مفتاح التوقيع `app/muawin.keystore` موجود في المستودع عمداً حتى تتحدث النسخ فوق بعض بدون حذف. لا تستخدمه لأي تطبيق آخر.

## شكر

- التصميم مستوحى من [tshi-dev/UrTV](https://github.com/tshi-dev/UrTV).
- الخطوط: ثمانية (Thmanyah)، و Noto Naskh Arabic لرمز ﷺ (OFL).
- الصور: Unsplash، انظر [PHOTO-CREDITS.md](PHOTO-CREDITS.md).
