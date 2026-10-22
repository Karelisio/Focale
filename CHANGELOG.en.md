# What's new in Focale

English version of CHANGELOG.md, with the same "## X.Y.Z" sections in the same order (most
recent first). Focale shows these notes when its language is not French; the CI adds this
version's section below the French one in the GitHub release description.

## 1.10.0

- 360° photos and panoramas: "360°" opens an immersive view where you look around with your
  finger or by moving the phone; "Panorama view" pans across a panorama at the height of the
  screen.
- Cast to a TV: the current photo, video or slideshow is shown on a connected screen (HDMI,
  USB-C) or wirelessly (Miracast, "Cast screen"), while the phone keeps the controls. In the
  standard version, send to TVs on your local network (DLNA: Samsung, LG, Sony, Kodi…), without
  Google Cast and without anything leaving the local network. The vault is never cast.
- Collages: select 2 to 9 photos, then "Create collage"; several layouts, formats, spacing,
  rounded corners and background; drag to frame, pinch to zoom, long-press to swap two photos.
- Print photos from the viewer or a selection, one photo per page.
- Language: Focale can be shown in French or English whatever the phone's language (Settings,
  or "App languages" on Android 13 and later). Dates, places, search, notifications and the
  widget follow.
- Large libraries: grids open and scrollbar jumps are about 8 times faster, the date bubble
  and returning from the viewer are much faster, and the analysis puts far less load on the
  database.

## 1.9.0

- Data backup (Settings): favorites, albums, people and their faces, hidden folders and
  settings in a password-encrypted file, to restore on a new phone once the photos have been
  copied. Also automatic, daily or weekly, to the folder of your choice (the last 3 are kept).
- Editor: add text to a photo (color, size, boxed background or outline, bold; moved with your
  finger, enlarged and rotated with two fingers), and blur an area to hide a face or a license
  plate (rectangle, ellipse or brush, blurred or pixelated, irreversibly). "Faces" blurs every
  detected face in one tap.
- Videos: playback speed from 0.25× to 2×, by tapping "1×" at the end of the playback bar;
  the sound keeps its pitch, and the speed applies to the following videos until the viewer
  is closed.

## 1.8.0

- Search: combine text and chips (people, place, period, type); results appear in the tab and
  follow each chip, removed with one tap. Dates, people and places recognized in what you type
  become a chip in one tap, and when nothing is found, Focale tells you which chip to remove.
- Long-press an album: slideshow, rename, hide from Photos, move to the vault or delete,
  without opening it. Also for albums in the vault.
- Videos: double-tap on the right to skip forward 10 seconds, on the left to go back; quick
  taps that follow add up (+20 s, +30 s…).
- Editor: straighten a photo with a graduated ruler or with two fingers; the crop follows,
  with no empty corner, in the chosen format.
- Vault: automatic encrypted backup, daily or weekly, to the folder of your choice (SD card,
  USB drive…); the last 3 are kept, and each one is restored with its password, never stored
  on the device.
- People: more reliable face grouping and "Same person?" suggestions, with
  thresholds measured on real photos.
- Memories: trips are detected better (a weekend 80 km away, a trip in stages; no more day
  trips or second homes counted as trips). Cities are named in French ("Lisbonne") and found
  by their French or English name.
- Large libraries: much faster timeline and folder list, and the analysis no longer slows down
  as it progresses.

## 1.7.0

- A photo taken out of the vault keeps its original date: it goes back to its place in the
  timeline instead of returning to the top (also for an album taken out of the vault, a
  restored backup, a copy or an edit saved as a copy).
- Vault: sort items (date taken, date added, name, size) and albums (name, date, number of
  items), remembered.
- Reliable moves to the vault: a long move continues in the background, with its progress in a
  notification, and resumes without re-encrypting anything if Focale is closed. An interrupted
  move no longer leaves a duplicate photo: if needed, "Remove from gallery".
- "N new photos in “Folder” to move to the vault" notification for albums linked to a folder,
  with "Move to vault" in one gesture.
- Vault trash: a deleted item stays there for 30 days before being erased.
- Restoring a vault backup: a photo that is already there joins its album.
- Saved rotation: the XMP orientation follows, for Lightroom and other software.

## 1.6.0

- Albums in the vault: "Move to vault" from an album, then add new photos without decrypting
  anything ("Add" button, and suggestion of new photos from the original folder). Vault
  backups keep the albums.
- Two-finger rotation in the viewer, with "Save rotation" (lossless for JPEG); also "Rotate"
  in the menu.
- Focale can become the default photo app: it appears in "Open with" (files, WhatsApp,
  camera…), and Settings > Files explains how to choose it.
- Cleanup (duplicates, blurry photos…) moved to the More tab.

## 1.5.0

- People: sort by number of photos, name or recent photo, and pinned people at the top
  (long-press).
- "Same person?": Focale suggests merging the groups of the same person, in one
  tap.
- Review a person's faces: least similar first, to remove or move with multiple selection,
  with undo.
- Photos together: from a person, "With…" shows the photos where you are together.
- Automatic albums: documents, receipts, animals, food, landscapes, parties, vehicles and
  sports, updated during the analysis.
- Richer memories: "2 years ago with Alice" and trips ("Lisbon · June 2023"), also in the
  widget.

## 1.4.1

- On-device analysis fixed: in published versions, labels, text in photos and faces failed
  without reporting it. The whole library is analyzed again.
- On failure, the progress names the analysis concerned and the error, instead of showing
  "Waiting".

## 1.4.0

- Detailed analysis progress in Search and Settings: labels and places, text in photos, faces
  and search by meaning, each with its counter.
- "Analyze now" button: starts the analysis right away, even when not charging if the "Only
  while charging" setting is on.

## 1.3.0

- Natural language search: "dog on the beach", "red car in the snow"…
- People: faces are grouped on the phone; name, merge or hide people from Albums, and search
  for them by name.
- Text in photos: search ("invoice", "wifi"…) and copy from the viewer.
- Drawing in the editor: pen, highlighter, line, arrow, rectangle, ellipse and eraser.
- Video editing: trim the start and end, mute the sound, saved as a copy.
- Motion photos: play with a long-press or the "Motion" chip, badge in the grid.
- Vault: password-encrypted backup, to restore on another phone.
- Video controls in Material You colors; rotating no longer restarts the video.
- More natural back transitions, without the screen shrinking.
- What's new shown after each update.

## 1.2.0

- Folders hidden from Photos, still visible in Albums.
- Cleanup: screenshots and large videos, with the space you can free up.
- Ultra HDR photos shown in HDR (Android 14 and later).
- What's new in an update readable before installing it.
- Faster startup and better organized settings.

## 1.1.0

- Built-in updates: Focale tells you about a new version and installs it.
- Long-press selection fixed, map visible, viewer bar decluttered in Material You colors.

## 1.0.0

- First version: timeline, albums, smart search, editing, vault, map, memories, slideshow and
  widget.
