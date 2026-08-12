# Soneme Mimic

Barcode and QR code scanner, library, and creator that allows you to scan barcodes and QR codes into a library and then reproduce them on-screen as needed. Useful for reproducing gym and library ID cards, loyalty cards, shareable wifi codes, or tickets without carrying the physical media bearing the code.

# Supported code types

 - QR Code
 - UPC-A
 - UPC-E
 - EAN-8
 - EAN-13
 - Code 39
 - Code 93
 - Code 128
 - ITF
 - Codabar
 - PDF417
 - Data Matrix
 - Aztec

Support is limited to what the ZXing library can both scan and reproduce.

# Target device properties

The Sonim XP3900 has the following constraints:

- 240x320
- Android 11 Go
- No touchscreen
- Options menu softkeys
- No Google Play Store or services
- App must be sideloaded as an .apk

# Application overview

Soneme Mimic has a tabbed interface with two tabs: Wallet, Temporary

Both tabs are identical in format, but divide the codes into two separate lists for easier management. For ease of reference these will be called List view, but just keep in mind there are two list views and their only difference is whether the items displayed are tagged as Wallet items or Temporary items.

The app will be based around ZXing Core 3.5.4 and the old android.hardware.Camera.

Barcode names must be unique, but are identified to the system by a uid. Name uniqueness applies across all saved codes, whether they're tagged for wallet or temporary tab. Name uniqueness is case-insensitive.

Soneme Mimic reproduces the decoded logical content and symbology, not the exact original arrangement of modules/bars. Codes are decoded, stored, and then re-encoded for clean display. The logical payload is the goal, not an exact recreation of the original scanned code.

Individual codes when stored have the following fields:
uid
name
collection
format
convention
payload
sortOrder
displayRotation
displayInverted

Disambiguation of "format" and "convention": "Format" is the type of code (e.g. UPC-A or QR). "Convention" is the semantic convention being used in the code if applicable (e.g. QR vCard, telephone, Wi-Fi, etc.). Convention is only used for QR format, and is null for other formats.

The application UI is portrait-only. Rotation settings rotate only the rendered code within Code view.

App starts up in Wallet tab.

# Views

## List

### Controls

 - Left and right buttons switch tabs
 - Up and down cycle through the list
 - Clicking opens Code view for the item
 - If Temporary tab, back button returns to Wallet tab. If Wallet tab, back button returns to launcher

### Main content

Listing of codes tagged with this tab

Each list item consists of:
 - Code name (marquee if out of room)
 - Code type

"Temporary" is merely a bucket. Items in Temporary tab do not expire automatically or behave differently from items in Wallet tab. They remain until explicitly deleted or moved to Wallet. It's merely an organizational helper.

### Options menu

 - Delete

   Blank menu slot if list is empty. Opens a menu with "Delete (name)?", options Delete and Cancel. Delete deletes the item. Cancel returns to the list.
   
 - Move up

   Blank menu slot if focused item is the first in its list. Reorders the Code in the list up one spot.

 - New

   Opens a menu with the options Scan and Generate, Scan selected by default.
   If Scan is chosen, switch to Scan view. If Generate is chosen, switch to Generate view.


## Scan

### Controls

 - Back button returns to list on last active tab

### Main content

Full-screen camera view with functional alignment reticle. Only send the camera region inside the reticle to ZXing.

On first run get runtime CAMERA permission. If denied, return to the originating List with a simple explanation.

Camera continuously autofocuses when supported. If continuous autofocus is unavailable, periodically request autofocus while scanning.

On detection of a supported code, gives one short confirmation vibration and switches to Generate view with fields populated. If Scan view was activated from wallet tab, the collection selector is set to Wallet. If activated from temporary tab, selector is set to Temporary.

Release the camera whenever Scan loses the foreground, return to originating list.

### Options menu

 - (blank)

 - Torch

   Activates the torch (deactivated automatically when Scan finishes or is canceled)

 - Reticle

   Toggles between a square and rectangular reticle


## Generate

### Controls

 - D-pad navigates fields
 - Back button returns to last active tab without saving

### Main content

Form controls governing creation of supported code types. May be prefilled by Scan view.

Fields always displayed:
Name - text
Collection - select (Wallet, Temporary)
Code type - list of supported code types

Subsequent fields displayed are determined based on code type requirements.

For:
 - UPC-A
 - UPC-E
 - EAN-8
 - EAN-13
 - Code 39
 - Code 93
 - Code 128
 - ITF
 - Codabar
Value field with validation appropriate to that symbology.

For:
 - Aztec
 - Data Matrix
 - PDF417
Content field with validation appropriate to that symbology.

For:
 - QR
Content type selector with following types:
 - Text
 - URL
 - Wi-Fi
 - Contact
 - Email
 - Phone
 - SMS
 - Location
 - Calendar
 - Raw
Human-friendly editing fields based on payload type

QR codes are stored with a type and payload string, though for editing purposes the payload string is decoded based on the type and broken out into separate human-friendly editing boxes for the fields of the semantic payload (e.g. "tel:" links ask for a telephone number and do not require the user to type "tel:"). These fields are not stored individually. On save, if they have been edited, they are merely converted to the payload string, and that's what is stored. If a payload string already exists (e.g. from scanning) it is broken out into these fields, but unless the user edits one or more they are not converted into a new payload string and the original is simply retained.

On save displayRotation and displayInverted are reset. Editing an existing code therefore resets its rotation and inversion.

If a code item already existed in Wallet or Temporary and it is moved to the other collection, it is placed at the end of that collection's order.

### Options menu

 - Scan

   Switches to Scan view with values of name and collection passed for pre-fill when it next calls Generate view. Meant for use if initial scan fails or multiple were present and the wrong one was captured, or if editing an existing code to update the type and/or payload.

 - (blank)

 - Save

   Saves this code to the library. Save appears only when Name is non-empty and unique and all fields required by the selected code type contain values that can legally be encoded. On save, go to tab of the list's collection and focus the newly-saved item.


## Code

### Controls

 - Back button returns to last active tab

### Main content

Draws BitMatrix of code for scanning.

Save the current Activity brightness before displaying the code. Set brightness to maximum while the Code view is active. Restore the previous brightness whenever Code view is exited, paused, or destroyed.

Set screen brightness to maximum
Prevent the display from sleeping
Use pure #000000 and #FFFFFF
Maximize integer module size
Preserve a generous quiet zone

Some codes should be rotated 90 degrees clockwise by default to take advantage of additional vertical screen space as the screen has a 3:4 aspect ratio and bias for the right-handed tendency to swing the flip phone outward from the body when facing the screen toward a scanner horizontally. Encode the symbol and choose whichever of 0° or 90° gives the largest integer module scale inside the available display rectangle and quiet zone. If rotation wins, choose 90° clockwise as the default rotation.

All codes should display in black and white by default.

Changing a code's rotation or inversion is remembered, though not expressed as an editable field in Generate. Subsequent viewing of the code should render it in its last state of rotation and inversion.


### Options menu

 - Edit

   Opens pre-filled Generate view for this uid allowing renaming and editing

 - Invert

   Toggles render between black on white (default) and white on black for the rare scanner set up for inverse symbols. Last invert state is remembered per code.

 - Rotate

   Rotates the code 90 degrees counter-clockwise (repeatable). Last rotation state is remembered per code.