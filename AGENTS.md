# Notes for people and agents working on One More Core

Two people work on this repository, each with an agent. Read this before changing a system below, and keep it true: when you
change how one of them works, change its lines here in the same commit.

## How to work together

- Work lives in GitHub Issues. Assign yourself, branch from `main`, open a draft PR early, write `Closes #N`.
- `main` takes only PRs: CI runs the GameTests with and without Diegesis, and someone else approves. Use auto-merge.
  Each push to `main` makes a `dev-<sha>` prerelease and a Discord post.
- Commit small, one topic per commit, with a clear message (what and why).
- Run the GameTests before you push: `./gradlew runGameTestServer` must end with "All N required tests passed". A test that
  fails after your change shows a behaviour someone relies on: fix the cause, do not delete or loosen the test.
- No vanilla block is a seat or table (`onemorecore:seats`, `onemorecore:serving_surfaces`). The GameTests still
  build with them: `src/main/resources/gametest_pack` adds them back, loaded only on the GameTest server (`OneMoreCore`,
  `GameTestHooks.isGametestServer`, always false in production). Test: `FixesGameTests.vanillaBlocksAreNoFurniture`.
- When you change a behaviour on purpose, change its test in the same commit and say so in the message.

## Systems and what must keep working

- **Restaurant levels** (`Restaurant`, `Computer`, `appLevels`, off by default): editors define up to 64 paid area, wall or move steps. Move targets selected on a wall sign's upper `screen_part` use the sign below. Purchases require a closed restaurant, preserve all `onemorecore` blocks during wall removal, refresh machines, and carry level progress with the sign. See `LevelGameTests`.

- **Screen** (`menu_board`, `ScreenBlock`, `ScreenLayout`, models from `art/screen/screen_models.py`): stands on a floor (post,
  two sides), on a wall (bracket, front only) or under a ceiling (rods, two sides); upright or wide; wall and ceiling screens
  turn down (TILT). Same-kind neighbours join (`connect_*` state, `ScreenGroups`): one picture over all, drawn by the top-left
  one (the anchor). Each side has its own settings (`ScreenSettings.face`/`withFace`: bits 0-10 front; back in bits 16-25 and
  27, bit 26 back set). What does not fit scrolls by itself, smoothly, as a ticker: off, down or sideways (`ScreenSettings.scroll`,
  `ScreenPainter.offsets`); no pages turn. The menu uses the orders' colours, as rows or as a fast food board (`ScreenSettings.style`, bit 11 front / 28 back; `ScreenPainter.board`: a panel per kind, a card per dish). Test: `ScrollGameTests`. A screwdriver click (or a sneak-click with an empty hand) opens the settings window at the side it hits.
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
  shows a window of at most 2.4:1 from its start (`ScreenStationBehavior`); a movement key or an arrow moves the window a whole
  window over (`ScreenRenderer.panX/panY`), and at the end of the picture the key goes on to Diegesis. The scene keeps the blocks
  around (`clear_view`).
- **Screen drawing**: every fill uses `ScreenPainter.FILL`, which writes no depth and is not sorted: fills cover each other in the
  order drawn and text and items stay in front. Do not go back to fills that each stand a little in front (far away the depth
  buffer mixed them with the text). Text and items are cut at the scrolled area's edges by `ClipBuffers` (quads along the
  canvas axes are cut with their texture), so a row coming in shows its part in view.
  A floor or ceiling Screen turns in 16 directions (`TURN`), like a sign; a turned one joins no other.
- **Tray Rack**: holds up to six trays that players put in (`slot0..slot5` state); an empty rack gives nothing. A level keeps only
  the tray's kind, so a tray with dishes or a ticket is refused (`FixesGameTests.ticketSurvivesBreakAndRack`). Its hitbox is a
  shell 1 px thick, open at the front, the back and inside, so every level can be clicked. Tests:
  `ModelGameTests.rackHoldsTheTraysPutIn`, `FixesGameTests.trayRackIsAnOpenShell`.
- **Trays**: dishes with a block form (Display Delight plates, cakes) draw as that block, also with Diegesis
  (`CustomerRenderer.showsBlock`). `mixin/PlacedShapeMixin` gives Diegesis the same block, for the look, the hitbox and the
  rest pose up close (optional mixin, `required: false`). Up close the tray has one place more than its dishes: a ticket put
  anywhere on it becomes the label and takes no dish's place (`KitchenStations.TicketSlot`; test
  `TrayPlacementGameTests.diegesisTrayUsesAndPersistsPlacedPoses`).
  The tray keeps the whole ticket (`TrayBlockEntity.ticket`, item component `tray_ticket`), not only its table text: looking
  at the label shows the ticket's tooltip (table and dishes, `ClientSetup.renderOrderTooltip`), an empty-hand click on the label
  (`TrayBlock.onLabel`: middle of the front rim) gives the ticket back and does not open the close-up, and a new ticket hands
  the old one back (dropped on the tray up close). The ticket goes with the last dish served from the tray, as the label did
  before (on purpose). Tests: `TrayPlacementGameTests.labelTicketComesBack`, `FixesGameTests.ticketSurvivesBreakAndRack`.
  Every served dish lies on the table as a tray holds it: the turn and size it had on the tray (`TrayItem.serveDishes` passes
  the pose, `Customer.serve` puts it on the served copy as `tray_poses`), a dish from the hand or past an old tray's 3 places
  the tray's rest pose. Not its place: `CustomerRenderer.renderServed` draws the guest's dishes in one row (tray pixels 8, 3,
  13, the main dish in the middle) with `TrayRenderer.drawDish`, so two trays for one guest never overlap. `foodScale`
  sizes them (1 = the tray's size). Tests: `MoneyGameTests` (serveBread, `dishPastTheTraysPlacesHasNoPose`).
  The carried tray's hands and dishes move on springs (`TrayCarryRenderer.CarrySway`), stepped once per frame so a second
  render of the player in the same frame (the inventory's player picture) does not push them.
- **Grilling skewers** (`compat/GrillingSkewers`): Kaleidoscope Grilling items in `onemorecore:lying_dishes` or with an ID
  ending in `_skewer` lie horizontally on trays and serving surfaces. `TrayPlacerMixin` sets the close-up hand turn before
  Diegesis measures the preview. Test: `GrillingSkewerGameTests.grillingSkewersUseOptionalTagsAndScopedIds`.
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
- **Wiping** (Diegesis close-up): the client sends a stroke every tick, from where the cloth touched in the last tick to where
  it touches now (`WipingBehavior`); the server cleans every cell on the way and its neighbours (`Restaurant.wipeCells` with two
  ends, a stroke longer than a block counts as its end only), so the dirt goes as soon as the cloth passes. On purpose, the
  cloth reaches the whole table the close-up shows (up to 8 blocks from the station block), not only the player's reach. One
  stroke per tick, checked before the table search; a stroke touches only the blocks under it. Test:
  `MoneyGameTests.strokeWipesEveryCellOnItsWay`.
- **Order bubble**: a named customer (a chat viewer) shows its name in a row on top of the bubble, over a thin line, while the
  bubble shows; the vanilla name tag is hidden then (`CustomerRenderer.renderBubble`, `renderNameTag`).
- **Dish blacklist** (`DishBlacklist`): anything eaten or drunk can be a dish (food component, eat or drink use, `#c:foods`,
  `#c:drinks`) unless `config/onemorecore/dish_blacklist.json` names it, like JEI's blacklist: item ids and `#tags`, read again on
  `/reload`; a missing file gets the pack's sauces, fruit buckets and thrown potions (only the server writes it). An entry that is
  no string is left out, the others stay. `Restaurant.isFood` is `DishBlacklist.isDish`, so the menu catalog and ingredient
  recipes follow the list. Test: `DishBlacklistGameTests`.
- **Table Number card**: its place on the block is saved as `offset_x`/`offset_z` (a block entity's save writes its own
  position as `x`, `y`, `z`; never use those names in a block entity). Test: `ModelGameTests.tableNumberOffsetSurvivesSave`.
- **Ingredient unlocks** use `Ingredients`, `IngredientConfig` and `Restaurant.orderable`. Recipes and `ingredients.json` determine what customers, combos, menu screens and menu books can offer. A recipe needs at least one readable slot and a usable candidate in every slot. Free candidates satisfy a slot. Dishes whose indexed recipes are all unreadable or unusable need a valid override. The server rebuilds the index and logs their count on startup and reload. The sign carries purchased ingredient IDs and the remaining register balance. Legacy saves migrate ingredient purchases once. The Shop prices purchases on the server and the computer sends the capped ingredient lists on Menu requests. Purchase sources skip recycling inputs made from the result within two recipe levels, while preserving closed conversion cycles. See `IngredientGameTests`.
- **Hidden inputs** (`Ingredients.hidden`): a mod recipe's data (its codec as JSON) is read for inputs outside
  `getIngredients()`: every item, tag or fluid no visible slot has, except result/output, tool and look fields. A fluid is
  its bucket, or a `fluid/` key for a fluid with no bucket that only a recipe makes (never bought). A recipe with no item
  result makes its fluid (pressing tub: grapes to grape juice), so wine needs grapes. See `HiddenInputGameTests`.
- **Machines** (`Machines`, `requireMachines`, off by default): a recipe counts only when one of its recipe type's blocks
  is in the area, intermediate recipes too. Types to blocks: the built-in `onemorecore/machines.json` (vanilla and the pack's
  mods), overridden per type by `config/onemorecore/machines.json`; `compat/JeiMachines` sends JEI's catalysts and an operator's
  (or the singleplayer owner's) list adds the types no list has (a named type keeps its blocks: JEI's stove is no kettle). `Machines.kind` names a recipe whose type is not registered
  (Kaleidoscope) by the type's name. A type no list names uses the block with the same id, else needs no machine. The restaurant scans its area every 5 s (palette check per chunk
  section) and keeps the last result for unloaded parts. A missing machine shows as the first missing item. See `MachineGameTests`.
- **Shop couriers** (`Restaurant`, `Courier`, `CardboardBoxBlockEntity`): paid orders retain their remaining items and checkout destination in restaurant saved data until delivered. Couriers reference the assigned order by ID. Each restaurant starts at most one courier and delivers at most 16 stacks per tick, including overflow drops. A missing courier can be replaced, and a stale courier disappears. An unloaded spawn or expired 60-second route uses a stand-in at the destination. An unloaded destination is checked once a second until it loads. Legacy orders without a point use the sign. Boxes hold 27 stacks, preserve them on pickup and expose no hopper inventory. Their slots use vanilla shulker placement rules and reject nested cardboard boxes. See `CourierGameTests` and `MoneyGameTests.shopDeliveryUsesSharedTickBudget`.
- **Day start**: no title or status text from the server; each client darkens the screen with "Day N" and a chime when
  the restaurant it stands in opens (`client/DayFade`).
- **Computer boot** (`ComputerScreen`): once per opening (`ComputerCamera.booted`; the desktop after the free camera does not
  boot) the monitor powers on and shows boot text from the opening computer state, then the logo with a loading
  bar for about 3.5 s on `Util.getMillis()`. A click or a key skips the boot. Movement keys and the game's screenshot and
  fullscreen shortcuts (F2 and F11 by default) keep their usual functions. After Escape skips the boot, release it before
  pressing it again. Escape on the desktop turns the CRT off (0.3 s) before `ComputerCamera.leave`. The other ways out leave
  at once. App icons: `art/computer/make_icons.py`.
- **Windows**: the mod's windows use `client/Ui` (see-through black panel, letterbox bars, pill buttons). New windows use it too.

## Art

Models and textures are made in Blockbench (`art/*.bbmodel`); keep the `.bbmodel` next to what the mod uses. Every new item
needs a look in the inventory, in the hand (first and third person) and on the ground: set its `display` in the item model.
