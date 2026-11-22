# Notes for people and agents working on One More Core

Two people work on this repository, each with an agent. Read this before changing a system below, and keep it true: when you
change how one of them works, change its lines here in the same commit.

## How to work together

- Pull before you start (`main`). Commit small, one topic per commit, with a clear message (what and why).
- Run the GameTests before you push: `./gradlew runGameTestServer` must end with "All N required tests passed". A test that
  fails after your change shows a behaviour someone relies on: fix the cause, do not delete or loosen the test.
- When you change a behaviour on purpose, change its test in the same commit and say so in the message.

## Systems and what must keep working

- **Screen** (`menu_board`, `ScreenBlock`, `ScreenLayout`, models from `art/screen/screen_models.py`): stands on a floor (post,
  two sides), on a wall (bracket, front only) or under a ceiling (rods, two sides); upright or wide; wall and ceiling screens
  turn down (TILT). Same-kind neighbours join (`connect_*` state, `ScreenGroups`): one picture over all, drawn by the top-left
  one (the anchor). Each side has its own settings (`ScreenSettings.face`/`withFace`: bits 0-9 front, 16-25 back, bit 26 back
  set). A screwdriver click cycles the side it hits (off, menu, orders); sneak-click opens the settings window.
  Tests: `ScreenGameTests.screensJoinAndShowBothSides`, the order card tests.
- **Pictures** (`ScreenPicture`): the only map from canvas units to the world. The renderer, the pointer and the server's
  click check (`ScreenClick`) all use it; do not add a second one. Keep world points in double (a float loses the position far
  from 0,0).
- **Orders on a screen** (`OrderLayout`): the client draws and the server checks clicks with the same layout, so it must not
  use text widths. Clicks carry the side, the page and the scroll.
- **Order Screen** (`order_board`) and **Monitor**: blocks kept for old worlds; their items are gone (they drop a Screen).
- **Tray Rack**: holds up to six trays that players put in (`slot0..slot5` state); an empty rack gives nothing. Test:
  `ModelGameTests.rackHoldsTheTraysPutIn`.
- **Trays**: dishes with a block form (Display Delight plates, cakes) draw as that block, also with Diegesis
  (`CustomerRenderer.showsBlock`).
- **Customers**: `walkSpeed` 0.26; a customer whose path does not move it steps onto the next free node (`Customer.hopAlong`).
- **Stream chat**: the computer's Stream app sets `chatPlatform`, `chatChannel`, `chatCommand` (operators only) and connects
  again at once (`SkinPool.configure`). Mods > One More Core > Config shows every option.
- **Open sign**: chalkboard on a floor, Art Deco blade on a wall, plaque under a ceiling (`OpenSignRenderer`, models from
  `art/models/open_sign.bbmodel`).
- **Menu book**: the list page is a printed menu (a heading per kind, a line per dish with dots to the price; `RecipeBook.MenuRows`).
- **Windows**: the mod's windows use `client/Ui` (see-through black panel, letterbox bars, pill buttons). New windows use it too.

## Art

Models and textures are made in Blockbench (`art/*.bbmodel`); keep the `.bbmodel` next to what the mod uses. Every new item
needs a look in the inventory, in the hand (first and third person) and on the ground: set its `display` in the item model.
