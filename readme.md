# Soneme Mimic

![Soneme Mimic Icon](https://github.com/userexec/soneme-mimic/blob/master/soneme-mimic-icon.png?raw=true)

Soneme Mimic is a small, keypad-friendly Android barcode and QR-code wallet built for the Sonim XP3Plus XP3900.

It can scan supported barcodes and QR codes with the phone camera, save them into a simple library, and reproduce them on the screen later. It is useful for things such as gym cards, library cards, loyalty cards, tickets, Wi-Fi QR codes, and other situations where carrying the physical card or printed code is inconvenient.

The interface is designed around the XP3900's D-pad and three Sonim softkeys. There are no touch controls.

Mimic does not try to preserve a photographed barcode pixel-for-pixel. It reads the logical data and barcode type, stores that information, and generates a clean new code when you display it.

**Note: The XP3900's camera hardware is not really up to the task of scanning small barcodes in many cases. You may need to look up what kind of code it is and manually enter it. This is generally not an issue at all with QR codes, but small keytag 1D codes can be a real challenge. Guessing Code 128 or Codabar usually works out, but you may be doing some comparison.**

![List interface](https://github.com/userexec/soneme-mimic/blob/master/screenshot-list.png?raw=true)  ![New interface](https://github.com/userexec/soneme-mimic/blob/master/screenshot-new.png?raw=true)  ![Generate interface, QR](https://github.com/userexec/soneme-mimic/blob/master/screenshot-generate-qr.png?raw=true)  ![Generate interface, codabar](https://github.com/userexec/soneme-mimic/blob/master/screenshot-generate-codabar.png?raw=true)  ![Code interface, 128](https://github.com/userexec/soneme-mimic/blob/master/screenshot-128.png?raw=true)  ![Code interface, codabar](https://github.com/userexec/soneme-mimic/blob/master/screenshot-codabar.png?raw=true)  ![Code interface, QR](https://github.com/userexec/soneme-mimic/blob/master/screenshot-qr.png?raw=true)

## Features

* Scan supported 1D barcodes and 2D codes with the phone camera
* Save codes into Wallet or Temporary collections
* Manually create codes when scanning is not practical
* Edit, rename, move, reorder, and delete saved codes
* Display codes at maximum screen brightness
* Automatic display rotation when another orientation gives a more usable barcode
* Manual Rotate and Invert controls
* Persistent per-code rotation and inversion
* Square or rectangular scan reticle
* Camera torch control
* Human-friendly QR editing for common QR payload types
* Sonim softkey integration
* No accounts, analytics, advertising, subscriptions, or cloud services

## Supported Code Types

Soneme Mimic supports:

* QR Code
* UPC-A
* UPC-E
* EAN-8
* EAN-13
* Code 39
* Code 93
* Code 128
* ITF
* Codabar
* PDF417
* Data Matrix
* Aztec

Support is intentionally limited to code types that the ZXing library can both read and reproduce.

## Tested Devices

Soneme Mimic has been developed and tested on:

* Sonim XP3Plus XP3900 — Android 11 Go

The interface is specifically designed for the XP3900's 240x320 non-touch display and native three-position Sonim softkey bar.

Other Android devices are not a target. A normal touchscreen phone probably will not have the Sonim softkeys the interface expects, and parts of the application may be impractical or inaccessible without them.

## Installing

Soneme Mimic is distributed as a normal Android APK.

Copy the APK to the device and install it, or install it from a connected computer with ADB:

    adb install soneme-mimic.apk

If updating an existing release signed with the same release key:

    adb install -r soneme-mimic.apk

Android may require permission to install apps from unknown sources when installing directly on the phone.

The first time Scan is used, Android will also request camera permission.

## Wallet and Temporary

The main screen contains two tabs:

### Wallet

Wallet is intended for codes you expect to keep around: gym cards, library cards, loyalty cards, recurring tickets, and similar things.

### Temporary

Temporary is simply a second organizational bucket for codes you do not necessarily want mixed into the main Wallet list.

Temporary codes do **not** expire automatically. They remain there until you delete them or move them to Wallet.

Use Left and Right on the D-pad to move between the two tabs. Use Up and Down to move through the saved codes. Press the D-pad center button to display the focused code.

Saved names must be unique across both collections.

## Adding a Code

Choose **New** from either Wallet or Temporary.

The menu offers:

* **Scan** — use the camera to read an existing code
* **Generate** — enter the code manually

The current tab determines the initial collection for the new code, but this can be changed before saving.

## Scanning

Choose **New**, then **Scan**.

Aim the camera so the code is inside the on-screen reticle. Mimic waits for repeated consistent reads rather than accepting the first plausible camera frame, which helps prevent blurry or incomplete barcodes from being mistaken for something else.

A successful scan gives a short vibration and opens the Generate screen with the detected format and data filled in. Give the code a unique name, make any desired changes, then save it.

The Scan softkeys include:

* **Torch** — toggles the camera light
* **Reticle** — switches between square and rectangular targeting areas

The square reticle is the default and works well for QR and other 2D codes. The rectangular reticle can be useful when isolating a 1D barcode from a crowded page or card.

### Camera limitations

The XP3900 camera is the limiting factor in some scanning situations.

Its camera can have difficulty resolving small, dense, or poorly printed 1D barcodes sharply enough for reliable decoding. This is particularly noticeable when the physical barcode is small enough that the camera must be held very close, where focus, noise, glare, and shadows become a problem.

If Mimic reliably scans a larger reproduction of a barcode but cannot read the small original, there may simply not be enough useful image detail coming from the camera. No amount of software enthusiasm can reconstruct bars the sensor never resolved.

In that case, use **Generate** and enter the printed value manually. Mimic can still reproduce the barcode cleanly even when the phone camera cannot scan the original.

## Generating a Code Manually

Choose **New**, then **Generate**.

Every code has:

* **Name** — the friendly name shown in Wallet or Temporary
* **Collection** — Wallet or Temporary
* **Code type** — the barcode or 2D-code format

The remaining fields depend on the selected type.

For ordinary 1D barcodes such as Code 128, EAN, UPC, ITF, or Codabar, enter the value to encode.

Aztec, Data Matrix, and PDF417 accept general content.

QR codes provide a content-type selector with human-friendly fields for:

* Text
* URL
* Wi-Fi
* Contact
* Email
* Phone
* SMS
* Location
* Calendar
* Raw

For example, a Phone QR asks for the phone number rather than requiring you to manually type a `tel:` URI. Wi-Fi, contact, email, and other structured QR types are handled similarly.

**Raw** is available when you need direct control of the QR payload or when Mimic scans a QR code whose convention it does not recognize.

The Generate softkeys are:

* **Scan** — replace or fill the code data by scanning
* **Cancel** — return without saving
* **Save** — save the code when all required fields are valid

Back also returns without saving, but Cancel is useful when focus is currently inside a text field.

## A Note About Library-Card Codabar

Codabar has historically been common on library cards.

One potentially confusing detail is that the number printed below a library barcode may omit the Codabar start and stop characters even though those characters are part of the encoded data.

A common library-card Codabar convention uses `A` on both sides.

For example, if the card visibly prints:

    23629001321735

the actual Codabar payload may be:

    A23629001321735A

If a known Codabar library card will not scan and you are recreating it manually, include the `A` guards when they are part of the original barcode.

The printed bars themselves may also look a little uneven on old or inexpensive library-card printers. Slightly different apparent narrow-bar widths do not necessarily mean the barcode is a different symbology.

## Displaying a Saved Code

Select a saved code from Wallet or Temporary.

Mimic switches to a dedicated Code view and:

* raises the display to maximum brightness,
* prevents the screen from sleeping,
* renders the code in pure black and white,
* preserves barcode geometry and quiet space rather than stretching a code merely to fill the display.

For long 1D barcodes, Mimic may rotate the code automatically so the available 240x320 display gives it the largest practical whole-pixel module size.

This can leave apparently unused white space around some barcodes. That is intentional. Barcode bars and spaces are discrete geometry, and a smaller mathematically correct barcode is more useful than a larger distorted one.

The Code softkeys are:

* **Edit** — edit the saved code
* **Invert** — switch between black-on-white and white-on-black
* **Rotate** — rotate the code 90 degrees

Rotation and inversion are remembered separately for each saved code.

The display brightness is restored when Code view is closed.

## Editing Codes

From Code view, choose **Edit**.

You can change the name, collection, code type, and content.

Choosing **Scan** while editing allows the existing saved item to be replaced with a newly scanned code without having to delete and recreate the entry.

If a code is moved between Wallet and Temporary, it is placed at the end of the destination list.

Saving an edited code resets its display rotation and inversion so the regenerated code can choose a fresh default presentation.

Cancel or Back returns without saving changes.

## Reordering and Deleting

From a Wallet or Temporary list:

* **Move up** moves the focused code one position earlier in that collection.
* **Delete** asks for confirmation before permanently removing the focused code.

Moving an item affects only its order within that collection.

## What Mimic Stores

Mimic stores the decoded payload and code format, not a photograph of the original barcode.

When a scanned code is displayed later, Mimic generates a new clean representation of the same logical code.

For recognized QR conventions, Mimic can show friendly editing fields. If a scanned QR code is saved without changing those semantic fields, the original decoded payload is retained rather than unnecessarily rebuilding it.

This makes Mimic a code wallet, not an image wallet.

## Storage and Privacy

Soneme Mimic is intentionally local.

It does not require:

* an account,
* internet access,
* Google Play Services,
* analytics,
* advertising,
* a subscription,
* cloud storage.

Saved codes and application settings remain on the device.

Camera access is used only for scanning codes.

As with any barcode wallet, a displayed barcode should be treated with the same care as the physical card or ticket it represents. Anyone who can copy a usable barcode may be able to use whatever system accepts that barcode.

## Building

Soneme Mimic is a standard Gradle Android project.

A debug build can be produced with:

    ./gradlew assembleDebug

A configured release build can be produced with:

    ./gradlew assembleRelease

The resulting APK is written beneath:

    app/build/outputs/apk/

Release builds must be signed with an Android signing key before installation. Future updates must use the same signing identity as the installed release.
