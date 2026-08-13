# Soneme Mimic

![Soneme Mimic Icon](https://github.com/userexec/soneme-mimic/blob/master/soneme-mimic-icon.png?raw=true)

Soneme Mimic is a small, keypad-friendly Android card wallet built for the Sonim XP3Plus XP3900.

It can scan supported barcodes and QR codes, save and reproduce them on-screen, store photos of the cards they belong to, and keep plain-text card information that has no machine-readable code at all. It is useful for things such as gym cards, library cards, loyalty cards, tickets, insurance cards, membership numbers, Wi-Fi QR codes, and other situations where carrying the original physical card is inconvenient.

The interface is designed around the XP3900's D-pad and three Sonim softkeys. There are no touch controls.

For barcode and QR items, Mimic does not preserve a photographed code pixel-for-pixel. It reads the logical data and barcode type, stores that information, and generates a clean new code when you display it. Photos are optional attachments to the saved item and are kept separately from the generated code.

**Note: The XP3900's camera hardware can struggle with small physical barcodes. Scan view starts at approximately 2x zoom, and D-pad Up/Down can change zoom to help the camera focus from a more useful distance, but some small or poorly printed 1D codes may still need to be entered manually. QR codes are generally much less troublesome.**

![List interface](https://github.com/userexec/soneme-mimic/blob/master/screenshot-list.png?raw=true)  ![New interface](https://github.com/userexec/soneme-mimic/blob/master/screenshot-new.png?raw=true)  ![Generate interface, QR](https://github.com/userexec/soneme-mimic/blob/master/screenshot-generate-qr.png?raw=true)  ![Generate interface, codabar](https://github.com/userexec/soneme-mimic/blob/master/screenshot-generate-codabar.png?raw=true)  ![Code interface, 128](https://github.com/userexec/soneme-mimic/blob/master/screenshot-128.png?raw=true)  ![Photo interface](https://github.com/userexec/soneme-mimic/blob/master/screenshot-photo.png?raw=true)  ![Code interface, codabar](https://github.com/userexec/soneme-mimic/blob/master/screenshot-codabar.png?raw=true)  ![Code interface, QR](https://github.com/userexec/soneme-mimic/blob/master/screenshot-qr.png?raw=true)  ![Code interface, plain text](https://github.com/userexec/soneme-mimic/blob/master/screenshot-plaintext.png?raw=true)

## Features

* Scan supported 1D barcodes and 2D codes with the phone camera
* Adjustable scan zoom for difficult small barcodes
* Save items into Wallet or Temporary collections
* Manually create codes when scanning is not practical
* Store plain-text headings and values for cards with no barcode
* Attach one or more photos to any saved item
* Capture photos directly inside Mimic with torch and adjustable zoom
* Edit, rename, move, reorder, and delete saved items
* Display codes at maximum screen brightness
* Automatic display rotation when another orientation gives a more usable barcode
* Manual code rotation with persistent per-code rotation
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

Mimic also supports **Plain text and headings** as a non-barcode item type.

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

The first time a camera feature is used, Android will also request camera permission.

## Wallet and Temporary

The main screen contains two tabs:

### Wallet

Wallet is intended for items you expect to keep around: gym cards, library cards, insurance cards, loyalty cards, recurring tickets, and similar things.

### Temporary

Temporary is simply a second organizational bucket for items you do not necessarily want mixed into the main Wallet list.

Temporary items do **not** expire automatically. They remain there until you delete them or move them to Wallet.

Use Left and Right on the D-pad to move between the two tabs. Use Up and Down to move through the saved items. Press the D-pad center button to display the focused item.

Saved names must be unique across both collections.

## Adding an Item

Choose **New** from either Wallet or Temporary.

The menu offers:

* **Scan** — use the camera to read an existing barcode or QR code
* **Generate** — create an item manually

The current tab determines the initial collection for the new item, but this can be changed before saving.

## Scanning

Choose **New**, then **Scan**.

Aim the camera so the code is inside the on-screen reticle. Mimic waits for repeated consistent reads rather than accepting the first plausible camera frame, which helps prevent blurry or incomplete barcodes from being mistaken for something else.

Scan starts at approximately **2x zoom** because the XP3900 camera often focuses more reliably when it can be held farther from a small barcode.

While scanning:

* **D-pad Up/Down** — zoom in or out
* **Torch** — toggle the camera light
* **Reticle** — switch between square and rectangular targeting areas
* **Back** — cancel scanning and return

The square reticle is the default and works well for QR and other 2D codes. The rectangular reticle can be useful when isolating a 1D barcode from a crowded page or card.

Changing zoom also restarts the scanner's confirmation process so a result is not assembled from frames taken at different zoom levels.

A successful scan gives a short vibration and opens Generate with the detected format and data filled in. Give the item a unique name, add any photos you want, make any desired changes, then save it.

### Camera limitations

The XP3900 camera is the limiting factor in some scanning situations.

Its camera can have difficulty resolving small, dense, or poorly printed 1D barcodes sharply enough for reliable decoding. Zoom helps substantially because it allows the phone to be held farther from the code, where focus is often better and the phone is less likely to cast a shadow over the card.

If Mimic reliably scans a larger reproduction of a barcode but cannot read the small original, there may simply not be enough useful image detail coming from the camera. No amount of software enthusiasm can reconstruct bars the sensor never resolved.

In that case, use **Generate** and enter the printed value manually. Mimic can still reproduce the barcode cleanly even when the phone camera cannot scan the original.

## Creating an Item Manually

Choose **New**, then **Generate**.

Every item has:

* **Name** — the friendly name shown in Wallet or Temporary
* **Collection** — Wallet or Temporary
* **Format** — Plain text and headings, or one of the supported barcode/2D-code types
* **Photos** — optional photos attached to the item

A new manually generated item defaults to **Plain text and headings**.

The Generate softkeys are:

* **Scan** — replace or fill code data by scanning
* **Cancel** — return without saving
* **Save** — save the item when all required fields are valid

Back also returns without saving, but Cancel is useful when focus is currently inside a text field.

### Plain text and headings

Plain text is intended for cards that have useful information but no barcode at all, such as many insurance or membership cards.

A new Plain text item begins with:

* Heading 1
* Text 1

Use **Add field** to append additional heading/text pairs.

For example:

    MEMBER ID
    1234567890

    GROUP NUMBER
    876543

    RX BIN
    012345

When displayed, headings appear in smaller text and their values appear beneath them in larger bold text. Use Up and Down to scroll if the item contains more information than fits on one screen.

Plain-text items do not have a Rotate control because there is no barcode image to rotate.

### Barcodes and 2D codes

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

If you switch between Plain text and a barcode format after entering incompatible content, Mimic warns before discarding that content.

## Photos

Any item can have photos attached to it, whether it contains a barcode, QR code, or plain text.

In Generate/Edit, the Photos section shows small thumbnails followed by a **+** tile. Select **+** to capture another photo. Selecting an existing thumbnail opens it for viewing.

There is no arbitrary photo-count limit; practical limits are the available storage on the device. Mimic loads small thumbnail versions in the editor and only one full photo at a time in the viewer.

### Capturing a photo

Photo capture is deliberately simple and is meant for things such as the front and back of an ID or insurance card.

It starts at approximately **2x zoom**, which works better with the XP3900 camera when photographing card-sized subjects.

Controls are:

* **D-pad center** or **Capture** — take the photo
* **D-pad Up/Down** — zoom in or out
* **Torch** — toggle the camera light
* **Cancel** or Back — return without taking a photo

New photos are not committed to the saved item until you choose Save in Generate/Edit.

### Viewing photos

From a displayed item, **Photos** appears in the center softkey position only when that item actually has photos.

The viewer displays one photo at a time without panning or zoom controls.

With multiple photos:

* **D-pad Left/Right** — previous or next photo
* **Previous/Next** softkeys — previous or next photo
* **Back** — return to the item's Code or Plain Text view

If there is only one photo, Previous and Next are omitted.

When a photo is opened from Generate/Edit, the center softkey becomes **Delete**. Deleting an existing photo while editing is staged just like other edits: it is not permanently removed unless the item is saved. Cancel discards added/deleted-photo changes along with the rest of the edit.

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

## Displaying a Saved Barcode or QR Code

Select a saved code item from Wallet or Temporary.

Mimic switches to a dedicated Code view and:

* raises the display to maximum brightness,
* prevents the screen from sleeping,
* renders the code in pure black and white,
* preserves barcode geometry and quiet space rather than stretching a code merely to fill the display.

For long 1D barcodes, Mimic may rotate the code automatically so the available 240x320 display gives it the largest practical whole-pixel module size.

This can leave apparently unused white space around some barcodes. That is intentional. Barcode bars and spaces are discrete geometry, and a smaller mathematically correct barcode is more useful than a larger distorted one.

The Code softkeys are:

* **Edit** — edit the saved item
* **Photos** — view attached photos, when photos exist
* **Rotate** — rotate the code 90 degrees

If there are no photos attached to the item, the Photos softkey is omitted rather than displaying a dead option.

Rotation is remembered separately for each saved code.

The display brightness is restored when Code view is closed.

## Displaying Plain Text

Plain-text items use a dedicated text view instead of Code view.

Each heading is shown in smaller text followed by its value in larger bold text. Up and Down scroll through longer items.

The available softkeys are:

* **Edit** — edit the item
* **Photos** — view attached photos, when photos exist
* third position blank

As with Code view, Photos is omitted if the item has no photos.

## Editing Items

From Code or Plain Text view, choose **Edit**.

You can change the name, collection, format, content, text fields, and attached photos.

Choosing **Scan** while editing allows an existing item to be updated from a newly scanned code without deleting and recreating it.

If an item is moved between Wallet and Temporary, it is placed at the end of the destination list.

Saving an edited barcode resets its display rotation so the regenerated code can choose a fresh default presentation.

Newly captured photos and pending photo deletions are staged while editing. **Cancel or Back discards all unsaved changes**, including photo additions and deletions.

## Reordering and Deleting

From a Wallet or Temporary list:

* **Move up** moves the focused item one position earlier in that collection.
* **Delete** asks for confirmation before permanently removing the focused item and its associated data.

Moving an item affects only its order within that collection.

## What Mimic Stores

For barcode and QR items, Mimic stores the decoded payload and code format rather than a photograph of the original barcode.

When a scanned code is displayed later, Mimic generates a new clean representation of the same logical code.

For recognized QR conventions, Mimic can show friendly editing fields. If a scanned QR code is saved without changing those semantic fields, the original decoded payload is retained rather than unnecessarily rebuilding it.

Plain-text items store their ordered heading/value pairs.

Photos are stored separately as attachments to the item. They do not affect the generated barcode data.

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

Saved items, text fields, photos, and application settings remain on the device. Attached photos are stored in Mimic's private application storage rather than being added to the phone's normal photo gallery, and application backup is disabled.

Camera access is used only for scanning codes and capturing attached photos.

Cards, photos, membership numbers, and displayed barcodes can contain sensitive information. Treat the phone with the same care you would the physical cards it replaces.

## Building

Soneme Mimic is a standard Gradle Android project.

A debug build can be produced with:

    ./gradlew assembleDebug

A configured release build can be produced with:

    ./gradlew assembleRelease

The resulting APK is written beneath:

    app/build/outputs/apk/

Release builds must be signed with an Android signing key before installation. Future updates must use the same signing identity as the installed release.
