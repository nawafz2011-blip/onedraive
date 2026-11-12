# CloudPlay ☁️🎮

تطبيق أندرويد يخلي وندرايف حقك يبان كأنه مجلد في الجوال (قراءة فقط)، والمحاكيات تشغّل الألعاب منه مباشرة بدون ما تنزّلها كاملة. التطبيق يجيب بس الجزء اللي يحتاجه المحاكي من الملف ويحفظه في كاش.

## المحاكيات والمجلدات

| المحاكي | المجلد في وندرايف | الصيغة الأفضل |
|---|---|---|
| DuckStation | `Games/PS1` | CHD |
| NetherSX2 | `Games/PS2` | CHD |
| PPSSPP | `Games/PSP` | CSO |
| Dolphin | `Games/GC-Wii` | RVZ |
| Citra | `Games/3DS` | `.3ds` / `.cci` |
| Yuzu / Eden | `Games/Switch` | XCI / NSP |
| M64Plus FZ | `Games/N64` | `.z64` |

> ⚠️ **Vita3K ما ينفع**، لأنه يثبّت الألعاب داخل الجوال وما يشغّلها من مجلد.

## طريقة التشغيل

### 1. سجّل تطبيقك في Azure (مرة وحدة بس)
1. ادخل [portal.azure.com](https://portal.azure.com) بحساب مايكروسوفت حقك.
2. روح **App registrations** ← **New registration**.
3. اكتب أي اسم، واختر **Personal Microsoft accounts only**.
4. في **Redirect URI** اختر **Public client/native (mobile & desktop)** واكتب: `cloudplay://auth`
5. اضغط **Register** وانسخ **Application (client) ID**.

### 2. ثبّت التطبيق
نزّل `CloudPlay.apk` من صفحة [Releases](../../releases/latest) وثبّته.

### 3. سجّل الدخول
افتح CloudPlay، الصق الـ Client ID، واضغط تسجيل الدخول.

### 4. اربط المحاكي
في كل محاكي: إعداد مجلد الألعاب ← ☰ ← **CloudPlay** ← اختر المجلد (مثلًا `Games/PS1`) ← **Use this folder**.

## الأمان 🔒
- صلاحية **Files.Read** بس، يعني التطبيق ما يقدر يعدّل أو يمسح شي من وندرايف.
- تسجيل الدخول بـ **PKCE** بدون أي كلمة سر مخزّنة في التطبيق.
- التوكنات مشفّرة **AES-256** بمفتاح في Android Keystore.
- ما فيه أي سيرفر وسيط، الجوال يكلم مايكروسوفت مباشرة.
- النسخ الاحتياطي مقفول، فالبيانات ما تطلع من الجوال.

## نصايح
- استخدم واي فاي سريع أو 5G.
- الكاش: 4 جيقا تكفي لـ PS1 و PSP، وللسويتش و PS2 خله 6 جيقا أو أكثر.

## (اختياري) مفتاح توقيع ثابت
عشان التحديثات تنثبّت فوق النسخة القديمة بدون ما تمسحها، أضف هذي في **Settings ← Secrets and variables ← Actions**:
`KEYSTORE_BASE64` (ملف الـ keystore بصيغة base64)، `KEYSTORE_PASSWORD`، `KEY_ALIAS`، `KEY_PASSWORD`.
بدونها كل بناء ينوقّع بمفتاح debug جديد، وتحتاج تمسح التطبيق قبل التحديث.
