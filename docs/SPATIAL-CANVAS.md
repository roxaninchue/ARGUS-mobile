# Spatial canvas, stage 1 (behind `spatial_canvas`, off by default)

The chat stays the only static surface; a scene graph is what the agent edits and the Design view is what draws it.
Stage 1 adds no renderer, no WebView and no frames to the chat: it adds a model, a schema, a store, and a tool that is
only registered when the flag is on.

## Flag

`Settings.bool("spatial_canvas", false)` — Settings › Look & feel → Spatial canvas (beta); the tool is absent until it is on.
`SceneStore.enabled` is the single place that reads it; when it is off the `scene` tool is not in the tool list,
nothing is written to `~/scenes`, and the prompt is byte-for-byte what it was before.

## The model (`scene/Scene.kt`, pure)

A scene is a flat list of nodes with optional parents (what the bundled three.js starter understands):

```
Scene(title, units, revision, nodes)
Node(id, shape, label, position, rotation, scale, size, material, parent, visible, animation)
```

- `shape`: box, sphere, cylinder, cone, torus, plane, capsule, group (aliases like `nosecone`, `ring`, `floor` map in).
- `size` is width × height × depth in scene units; the bridge turns it into each primitive's own parameters
  (`radius`, `height`, `tube`, a 2-value `size` for plane).
- Numbers are clamped, colours normalised to `#RRGGBB[AA]`, ids slugged and de-duplicated, parents that are missing,
  self-referential or looping are dropped with a note.
- **`MAX_NODES` = 120 per scene.** Overflow is dropped with a note, never silently: the Design view draws every node,
  and the low-end target (2–3 GB phones) has to stay at one scene per reply.
- `animation` (spin / bob / pulse, one axis, speed, amplitude) is live: it is carried into the page's parts JSON and
  applied by the starter. It changes the part's *inner* node only, so the root transform — what the Design picker, the
  arrow keys and the scene graph read and write — stays exactly the numbers in the graph.

## The DSL (`Scene.SCHEMA_JSON`)

A JSON Schema (draft 2020-12) for the object the model fills in:

```json
{ "action": "create | update | read | open", "title": "...", "units": "mm|cm|m",
  "nodes": [ ... ], "set_nodes": [ ... ], "remove": ["id"] }
```

The tool's parameter schema mirrors it, so a model that follows the tool cannot write something `Scene.normalize`
has to repair.

## The store (`scene/SceneStore.kt`)

```
~/scenes/current.json          { slug, title, revision, at, path }
~/scenes/<slug>/scene.json     the graph, pretty-printed
~/scenes/design.txt            the design file last handed to the Design view
```

- `create` writes a new scene and publishes it; `update` applies `set_nodes` / `remove` (or `nodes` to replace
  everything), bumps the revision and republishes; `read` returns the graph; `open` re-shows it.
- Unchanged edits are refused like every other tool ("that changed nothing"), and every reply names how many nodes
  there are, whether it drew, and what was dropped and why.

## The first renderer: the Design view

`Scene.spec` → `Designs.spec3d` → `Designs.run(create/update … parts)` → `~/designs/<slug>/index.html` with the scene
as its `blaze-parts` block. That means three.js, the WebGL1 fallback, the native drawing when the engine can't start,
orbit/pinch, Exit, versions and undo are all the code paths that already ship and are already tested.

## The search globe

A web search is shown, not just described. With the canvas on, `web_search` builds a scene of the search itself through
[`SearchGlobe`](../app/src/main/java/com/argus/assistant/scene/SearchGlobe.kt) and publishes it under its own slug
(`~/scenes/web-search/scene.json`, with the design path it created kept in `~/scenes/web-search/design.txt`), so it never
overwrites the scene the user is editing: an obsidian
globe, two spinning sweep rings, the query as a pulsing blue marker above it, and one steel marker per result placed
deterministically on the sphere and labelled with its site. `fetch_page` turns the page it opened white and pulsing, so
the globe shows what is being read as well as what was listed. The scene is derived from the tool output, never authored
by the model, and the whole thing is a no-op while `spatial_canvas` is off. The globe is brought up in front of the
chat as the search starts (`Views.open("design", …)`), then updated in place as more pages are listed and read, so the
pane follows the search rather than appearing after it.

Animations ride that page's existing render loop (no second `requestAnimationFrame`): `spin` turns, `bob` offsets and
`pulse` scales on the part's inner node, with frame time clamped so a slow frame can't make them jump. `prefers-reduced-motion`
(or `window.__blazeMotion = false`, which Blaze can set from its motion setting) freezes them, and a scene with no
animation block costs exactly what it did before.

The native fallback (no WebGL, or three.js won't start) plays the same three kinds from the same block: `PartsSketch`
applies the part's animation to its own geometry and recomposes its drawing on a ~30 fps clock, which only runs while
some part animates and the phone's motion setting is on. A child part is attached to its parent's root, exactly like the
page, so animating a group does not drag its children along.

The Design view shows a **Motion** button whenever a scene has an animation: `Motion 1×` → `Motion 0.35×` → `Motion paused`
→ back to `1×`. It is one rate for both renderers — the page reads `window.__blazeAnimSpeed` each frame and integrates an
animation clock from it (so a pause *holds* the pose instead of snapping to rest), and `PartsSketchView(speed = …)` stops
advancing its own clock at 0. The choice lasts while the design is open; a new version of it keeps the chosen rate.

## Performance

- One scene per turn at most, ≤ 120 primitives, no textures, no post-processing, no new animation loop.
- The tool is absent when the flag is off, so prompt tokens and first-token latency are unchanged for everyone else.
- `Scene.context` is capped (`MAX_CONTEXT_CHARS`), so a 120-node scene costs about a paragraph, not a page.

## What stage 2 adds (not in this change)

1. A `scene_update` path that turns user drags in Design back into graph mutations (the LLM receives the graph, not the
   HTML), so "make it taller" after a manual drag stays exact.
2. More motion than the three kinds (paths, easing, transitions between turns), and a per-scene motion switch.
3. A native renderer (Filament/SceneView) fed by the same graph, with the Design view kept as the fallback — the graph,
   the store and the tool stay unchanged.
