# دليل رفع وبناء وتشغيل وتجربة تطبيق درع سولي العائلي (Souly Child Companion v4.3.0)

## أولاً: خطوات رفع المشروع على مستودع GitHub جديد (New Repo) وبناء الـ APK تلقائياً

### الطريقة 1: عبر GitHub Actions تلقائياً (بدون الحاجة لتثبيت أي برامج على جهازك)
1. أنشئ مستودعاً جديداً فارغاً (New Repository) على حسابك في GitHub (مثلاً باسم `souly-child-companion-v43`).
2. فك ضغط الملف `souly_android_companion_v4_3_final_repo_ready.zip` في مجلد فارغ على جهازك أو داخل GitHub Codespaces، ثم ارفع الملفات إلى المستودع الجديد بالأوامر التالية:
   ```bash
   git init
   git branch -M main
   git add .
   git commit -m "Initial release: Souly Child Companion v4.3.0 (Android 8 to 15)"
   git remote add origin https://github.com/<YOUR_USERNAME>/<YOUR_NEW_REPO>.git
   git push -u origin main
   ```
3. بمجرد انتهاء أمر `git push`، افتح صفحة المستودع على GitHub واضغط على تبويب **Actions** في الأعلى:
   - ستجد عملية البناء **`Build Souly Child Companion v4.3 Multi-Edition APKs`** قد بدأت تلقائياً.
   - بعد انتهاء البناء (يستغرق حوالي 3 دقائق)، اضغط على عملية البناء المكتملة، وانزل لأسفل الصفحة تحت قسم **Artifacts** وحمّل الملف **`Souly-Child-Companion-v4.3-APK-All-Editions`** الذي يحتوي على الـ 4 نسخ الموقعة الجاهزة للتثبيت:
     - `Souly-Child-Companion-v4.3-Universal-Android8-to-15.apk` (النسخة الشاملة لجميع الهواتف)
     - `Souly-Child-Companion-v4.3-Android8-to-10.apk`
     - `Souly-Child-Companion-v4.3-Android11-to-13.apk`
     - `Souly-Child-Companion-v4.3-Android14-to-15.apk`

---

### الطريقة 2: البناء بضغطة واحدة من داخل GitHub Codespaces (VS Code)
إذا فتحت المستودع داخل **GitHub Codespaces**، افتح الـ Terminal وشغّل الأمر الواحد التالي فقط:
```bash
bash build_apks_codespace.sh
```
سيقوم السكربت تلقائياً بتثبيت `Android SDK 35` و `Gradle 8.7` وتوليد مفتاح التوقيع `souly-release-key.jks` وبناء جميع نسخ الـ APK ووضعها جاهزة للتحميل داخل مجلد `dist-apk/`.

---

## ثانياً: خطوات تثبيت التطبيق على الموبايل وتجربة الإمكانيات التسع
1. حمّل ملف **`Souly-Child-Companion-v4.3-Universal-Android8-to-15.apk`** على هاتف الأندرويد وثبّته.
2. **لهواتف أندرويد 13 و 14 و 15 فقط (فك الإعدادات المقيدة Restricted Settings)**:
   - افتح التطبيق واضغط الزر الأخير **`🔓 فتح إعدادات التطبيق (لتفعيل الإعدادات المقيدة في أندرويد 13–15)`**.
   - من صفحة معلومات التطبيق في إعدادات الهاتف، اضغط القائمة **`⋮`** أعلى الشاشة واختر **السماح بالإعدادات المقيدة (Allow restricted settings)**.
3. **تفعيل الصلاحيات العشر**:
   - اضغط على الأزرار المرقمة من **1 إلى 10** في شاشة التطبيق؛ سيتحول رمز كل زر إلى **`✅`** فور تفعيله، وسيظهر العداد في الأعلى **`(10 من 10)`**.
4. **ربط الهاتف بالمنصة (`soulyeg.online`)**:
   - من لوحة تحكم الوالدين على `https://soulyeg.online/parental-hub`، اضغط **إضافة جهاز جديد** للحصول على رمز الـ QR أو كود الربط (`SOULY-XXXX-1234`).
   - وجّه كاميرا التطبيق نحو الـ QR أو الصق الكود واضغط **`🔗 ربط الهاتف وتفعيل الحماية الآن`**.
   - إذا تركت حقل الاسم فارغاً، سيُسجَّل الهاتف تلقائياً بطرازه الحقيقي (مثل `Infinix X6880` أو `ZTE Nubia A76 5G`).
