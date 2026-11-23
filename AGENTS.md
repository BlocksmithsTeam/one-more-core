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
  one (the anchor). Each side has its own settings (`ScreenSettings.face`/`withFace`: bits 0-10 front; back in bits 16-25 and
  27, bit 26 back set). What does not fit scrolls by itself, smoothly, as a ticker: off, down or sideways (`ScreenSettings.scroll`,
  `ScreenPainter.offsets`); no pages turn. The menu uses the orders' colours. Test: `ScrollGameTests`. A screwdriver click cycles the side it hits (off, menu, orders); sneak-click opens the settings window.
  Tests: `ScreenGameTests.screensJoinAndShowBothSides`, the order card tests.
- **Pictures** (`ScreenPicture`): the only map from canvas units to the world. The renderer, the pointer and the server's
  click check (`ScreenClick`) all use it; do not add a second one. Keep world points in double (a float loses the position far
  from 0,0).
- **Orders on a screen** (`OrderLayout`): the client draws and the server checks clicks with the same layout, so it must not
  use text widths. Clicks carry the side, the flow (first page, down, sideways) and the offset as drawn (`ScreenClick`).
- **One restaurant per screen**: `Restaurant.setScreen` takes the screen from every other restaurant (`Restaurants.claimScreen`),
  and a removed restaurant tells the clients to forget its pictures. Two owners drew two pictures (menu and orders) on one
  screen. Test: `FixesGameTests.screenBelongsToOneRestaurant`.
- **Screen close-up** (Diegesis): a click on any cell opens it (the upper cell through `DiegesisHook.register`); a long screen
  shows a window of at most 2.4:1 from its start (`ScreenStationBehavior`); the movement keys, the arrows and a mouse drag move
  the window (`ScreenRenderer.panX/panY`; a click runs on release when the mouse did not move). The scene keeps the blocks
  around (`clear_view`).
  A floor or ceiling Screen turns in 16 directions (`TURN`), like a sign; a turned one joins no other.
- **Tray Rack**: holds up to six trays that players put in (`slot0..slot5` state); an empty rack gives nothing. Its hitbox is a
  shell 1 px thick, open at the front, the back and inside, so every level can be clicked. Tests:
  `ModelGameTests.rackHoldsTheTraysPutIn`, `FixesGameTests.trayRackIsAnOpenShell`.
- **Trays**: dishes with a block form (Display Delight plates, cakes) draw as that block, also with Diegesis
  (`CustomerRenderer.showsBlock`). `mixin/PlacedShapeMixin` gives Diegesis the same block, for the look, the hitbox and the
  rest pose up close (optional mixin, `required: false`).
- **Customers**: `walkSpeed` 0.26; a customer whose path does not move it steps onto the next free node (`Customer.hopAlong`);
  stuck within 4 blocks of its seat with no free node (behind a sofa's back), it sits down at once.
- **Stream chat**: the computer's Stream app sets `chatPlatform`, `chatChannel`, `chatCommand` (operators only) and connects
  again at once (`SkinPool.configure`; a pasted Kick or Twitch link works). A chat viewer comes once (`SkinPool.pick` takes the
  entry out); with no free name a customer keeps a default skin, so no two customers share one. Test:
  `FixesGameTests.chatViewerComesOnce`. Mods > One More Core > Config shows every option.
- **Open sign**: chalkboard on a floor, Art Deco blade on a wall (two blocks high: its upper half is a `screen_part`; blue when
  open, red when closed), plaque under a ceiling (`OpenSignRenderer`, models from `art/models/open_sign.bbmodel`). Without room
  above, or after a screwdriver click, a wall sign is the small chalkboard flat on the wall (`CHALK`).
- **Menu book**: a printed menu over both pages (a heading per kind, a line per dish with dots to the price; `RecipeBook.MenuRows`,
  a spread is two list pages). The kinds are bookmarks along the head of both pages, the search last (`RecipeBook.tabSlot`).
- **Table Number card**: its place on the block is saved as `offset_x`/`offset_z` (a block entity's save writes its own
  position as `x`, `y`, `z`; never use those names in a block entity). Test: `ModelGameTests.tableNumberOffsetSurvivesSave`.
- **Windows**: the mod's windows use `client/Ui` (see-through black panel, letterbox bars, pill buttons). New windows use it too.

## Art

Models and textures are made in Blockbench (`art/*.bbmodel`); keep the `.bbmodel` next to what the mod uses. Every new item
needs a look in the inventory, in the hand (first and third person) and on the ground: set its `display` in the item model.
