# WakiTaki Design System — "Command Center"

نسخه ۱.۰ — زبان طراحی اختصاصی اپلیکیشن اندروید WakiTaki (Jetpack Compose).

## ۱. هویت بصری

| توکن | مقدار | کاربرد |
|---|---|---|
| Background | `#0A0E14` | پس‌زمینه اصلی (Dark Graphite) |
| Navy | `#0F1B33` | لایه عمقی / گرادیانت هدر |
| Surface | `#111721` | پنل‌ها و کارت‌ها |
| SurfaceHigh | `#1A2230` | سطح فراتر (کلیپ‌ها، کیبورد chip) |
| Border | `#253041` | خطوط کنترلی |
| Emerald | `#2EE6A8` | رمزنگاری/آنلاین/موفقیت (accent اصلی) |
| Lime | `#C6F655` | اکشن/PTT/CTA |
| Teal | `#26C6DA` | تلمتری/دیباگ |
| TxRed | `#FF5470` | در حال ارسال (ON AIR) / خطا |
| Amber | `#FFB300` | هشدار/degraded/صف |

- Light Mode کامل (`StColors.Light*`) و Dark Mode کامل — هر دو از scheme واقعی Material 3 عبور می‌کنند.
- توکن‌های extended از طریق `LocalStExtended` در اختیار کامپوننت‌هاست؛ هیچ کامپوننتی رنگ هاردکد ندارد.
- شکل‌ها: `StShapes` (شعاع 8/12/18/26/34dp).
- موشن: `StMotion` (Fast=120ms, Normal=220ms, Slow=380ms).

## ۲. کتابخانه کامپوننت‌ها (`core/design/Components.kt`)

- `StButton` (Primary/Ghost/Danger، فیدبلمکش scale-on-press) و `StButtonFull`
- `StCard` (پنل با حاشیه‌ی کنترلی + نوار accent اختیاری + حالت کلیک‌پذیر)
- `StTextField` (ورودی با accent تمرکز)
- `StTopBar` / `StSectionLabel` (تیک accent + label مونواسپیس)
- `StAvatar` (رنگ قطعی بر اساس نام، انیمیشن هنگام SPEAKING)
- `StChip` (برچسب مونواسپیس + نقطه accent)، `StPulsingDot`
- `StSwitch` (توکن‌محور)
- `StWaveform` (Canvas-only، بدون bitmap — سبک روی دستگاه ضعیف)
- `PttButton` (کنترل اصلی PTT: حلقه‌های هاله‌ای متحرک، حالت‌های Idle/Queued/Connecting/ON AIR/Error، فشرده‌سازی scale، فیدبک label)
- `StSkeleton` (شیمر سبک)، `StateBlock` (loading/empty/error + اکشن)
- `ConnectionBadge`، `OfflineBanner`

## ۳. ناوبری و موشن

- ترنزیشن‌های NavHost: fadeIn/slideIn ورودی، fadeOut خروجی، pop معکوس (220/160ms).
- پالس‌ها فقط برای حالت‌های «فعال» (degraded، ON AIR، queue) — انیمیشن دائمی تزئینی وجود ندارد.

## ۴. صفحات بازطراحی‌شده

| صفحه | نکات |
|---|---|
| Welcome | لوگوی متحرک (حلقه‌های امنیتی)، کارت‌های accent |
| Permissions | کارت‌های جدا برای میکروفون/نوتیفیکیشن |
| Mode Select | دو کارت بزرگ کلیک‌پذیر |
| Login / Register | فرم کارتی، فیلد Invite، خطا inline |
| Device Verify | کارت emerald با Device ID |
| Home (Command Center) | نوار وضعیت + کارت رمزنگاری + هیروی PTT + دسترسی سریع کانال‌ها |
| Active Channel | TopBar، هیروی گوینده، نوار اعضا با presence، chips مسیر صدا، PttButton |
| Members | ردیف avatar + role/state chips + Remove (owner) |
| Channels (Create/Join/Invite) | کارت‌های فرم + QR با accent |
| Local Network | Host setup/Hotspot guide/Join با کارت‌های مرحله‌ای |
| Security | اثر انگشت‌ها، استراتژی کلید، reset sessions |
| Devices | لیست دستگاه‌ها + Revoke |
| Diagnostics | Event log در کارت + PROBE |
| Settings | بخش‌های Identity/Account/Network/Audio&Privacy + Log out (Danger) |

## ۵. قیود حفظ‌شده

- منطق صدا، Signal sender keys، WebSocket، floor control و مدیریت کلید دست‌نخورده ماند؛ فقط لایه UI تغییر کرد.
- انیمیشن‌ها Canvas/Scale/Fade (بدون Blur و Shadow سنگین) — مناسب دستگاه کم‌قدرت.
- `isMinifyEnabled=false` تا زمان پایدار شدن کامل، برای اطمینان از نبود crash مربوط به R8.

## ۶. اتصال پیش‌فرض سرور

`SettingsRepository.DEFAULT_API_BASE_URL` اکنون `https://wakitaki.sadsirco.ir/` است؛ نصب تازه اپ بدون تنظیم دستی به سرور وصل می‌شود.
