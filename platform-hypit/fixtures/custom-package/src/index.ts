// index.ts — C107-17 fixture component: the Badge surface.
//
// A deliberately small but REAL author package: module manifest, graph
// fragment, producer that seals a deterministic visual track, markup surface
// decoder and the host facet that registers it. The badge color lives in this
// package source (BADGE_PALETTE) so TC107-17-03 can prove that editing package
// code yields a NEW Build with different pixels while the old Result stays
// byte-identical. Project scope name — never @hypit/* (step 17.4).
import { sealVisualTrack } from "@hypit/hypit/composition";
import type { Timeline } from "@hypit/hypit/timeline";
import { compositionTypes } from "@hypit/hypit/composition";
import { timelineTypes } from "@hypit/hypit/timeline";
import { VISUAL_IR_V1 } from "@hypit/hypit/visual-ir";
import { canonicalize, createMarkupSurfaceHostFacet, sealGraphFragment } from "@hypit/hypit/author-kit";
import type {
  ComponentPackage,
  ModuleManifest,
  ProducerHandler,
  ProducerRef,
  StructuredSurfaceHandler,
  TypeRef,
} from "@hypit/hypit/author-kit";

export const badgeModule = { name: "@clone/custom-badge", version: "1" } as const;

export const badgeTypes = {
  badge: { module: badgeModule, name: "Badge" },
} satisfies Record<string, TypeRef>;

export const badgeProducers = {
  renderBadge: { module: badgeModule, name: "render-clone-badge" },
} satisfies Record<string, ProducerRef>;

/** Package-owned palette: editing this file must change future renders (TC107-17-03). */
export const BADGE_PALETTE: Readonly<Record<string, string>> = {
  brand: "#2456d8",
  alert: "#d8a024",
};

export const badgeManifest: ModuleManifest = {
  format: "hypit.module@1",
  name: badgeModule.name,
  version: badgeModule.version,
  dependencies: [{ module: timelineTypes.track.module }, { module: compositionTypes.visualTrack.module }],
  types: Object.values(badgeTypes).map(({ name }) => ({ name })),
  capabilities: [],
  producers: [
    {
      name: badgeProducers.renderBadge.name,
      inputs: [{ name: "timeline", type: timelineTypes.track }],
      outputs: [{ name: "track", type: compositionTypes.visualTrack }],
      needs: [],
    },
  ],
};

const render = (timeline: Timeline) => {
  const frames = Math.max(1, Math.round((timeline.durationSec * timeline.frameRate.numerator) / timeline.frameRate.denominator));
  return sealVisualTrack({
    programSpaceId: timeline.id,
    visualIr: VISUAL_IR_V1,
    id: "clone-badge",
    presents: [
      {
        id: "present-badge",
        span: { startFrame: 0, endFrameExclusive: frames },
        stacking: { order: 0, tieBreak: "badge" },
        elements: [
          {
            id: "badge-root",
            kind: "box",
            order: 0,
            style: [
              { name: "background-color", value: BADGE_PALETTE.brand! },
              { name: "position", value: "absolute" },
              { name: "left", value: 0 },
              { name: "top", value: 0 },
              { name: "width", value: "100%" },
              { name: "height", value: "100%" },
            ],
          },
        ],
      },
    ],
  });
};

const badgeHandler: { readonly handler: ProducerHandler } = {
  handler: ({ inputs }) => {
    const timeline = inputs.timeline;
    if (timeline?.value.kind !== "inline") throw new Error("badge producer requires an inline timeline input");
    return {
      outputs: { track: { kind: "inline" as const, value: canonicalize(render(timeline.value.value as Timeline)) } },
      needs: {},
    };
  },
};

export const badgeComponent = {
  producers: [{ producer: badgeProducers.renderBadge, ...badgeHandler }],
  validators: [{ type: badgeTypes.badge, handler: () => {} }],
} satisfies ComponentPackage;

/** The graph fragment behind <clone:Badge>: timeline in, visual track out. */
export const badgeFragment = sealGraphFragment({
  inputs: [{ name: "timeline", type: timelineTypes.track }],
  operations: [
    {
      id: "render-badge",
      producer: badgeProducers.renderBadge,
      inputs: { timeline: { kind: "fragment-input" as const, name: "timeline" } },
      result: { kind: "output" as const, name: "track" },
    },
  ],
  exports: [{ name: "track", type: compositionTypes.visualTrack, root: { kind: "fragment-operation" as const, operation: "render-badge" } }],
});

export const badgeSurfaces = [
  {
    name: "badge",
    tag: "Badge",
    mode: "structured" as const,
    outputs: [badgeTypes.badge, compositionTypes.visualTrack],
    vocabulary: {
      summary: "A deterministic project-package badge box.",
      appearance: "A fixed 200x120 box filled from the package palette.",
      attributes: [
        { name: "id", kind: "identifier" as const, required: true, summary: "Names this instance." },
        { name: "timeline", kind: "reference" as const, required: true, accepts: [timelineTypes.track], summary: "Selects the complete Timeline." },
      ],
      ports: [{ name: "track", type: compositionTypes.visualTrack, summary: "The terminal VisualTrack." }],
      example: '<clone:Badge id="badge" timeline={clock.timeline}/>',
      notes: ["Project-scope package: the namespace never shadows @hypit/*."],
    },
  },
];

export const decodeBadgeSurface: StructuredSurfaceHandler = ({ element, resolveReference }) => {
  const value = element.attributes.id;
  if (typeof value !== "string" || value.trim().length === 0) throw new Error("Badge.id must be text.");
  const id = value.trim();
  const timeline = element.attributes.timeline;
  if (typeof timeline !== "object" || timeline === null || (timeline as { kind?: string }).kind !== "reference") {
    throw new Error("Badge.timeline must be a reference.");
  }
  const resolved = resolveReference((timeline as { path: string }).path);
  if (resolved === undefined) throw new Error("Badge.timeline cannot resolve its path.");
  return {
    records: [
      { id: `${id}.value`, type: badgeTypes.badge, value: { kind: "inline" as const, value: { id } }, range: element.range },
    ],
    components: [
      {
        id,
        fragment: badgeFragment.id,
        inputs: { timeline: resolved.ref },
        outputs: { track: `${id}.track` },
        range: element.range,
      },
    ],
    fragments: [badgeFragment],
    exports: [`${id}.value`, `${id}.track`],
  };
};

export const hypitPackage = {
  format: "hypit.node-package@1" as const,
  modules: [{ manifest: badgeManifest }],
  components: [badgeComponent],
  hostFacets: badgeSurfaces.map((declaration) =>
    createMarkupSurfaceHostFacet({ module: badgeModule, declaration, handler: decodeBadgeSurface }),
  ),
};
export default hypitPackage;
