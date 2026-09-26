// companion.ts — C107-17 fixture Companion facet: makes the Badge surface
// editable in Studio. Follows the official *-studio package shape: one
// StudioTrackCompanion (entity = the badge track output), Inspector fields for
// the surface value and stacking, and source bindings for the references the
// decoder emits.
import { compositionTypes } from "@hypit/hypit/composition";
import { createStudioTrackCompanionHostFacet } from "@hypit/hypit/studio-adapter";
import type { StudioTrackCompanion } from "@hypit/hypit/studio-adapter";
import { badgeModule } from "./index.js";

export const badgeStudioCompanions: readonly StudioTrackCompanion[] = [
  {
    id: "badge",
    role: "track",
    output: { type: compositionTypes.visualTrack, surface: "badge", modules: [badgeModule] },
    family: "clone-badge",
    tone: "violet",
    label: "Clone Badge",
    icon: "component",
    requiredValues: ["value"],
    bindings: [
      { name: "timeline" },
      { name: "value", writable: true },
      { name: "stack", writable: true },
    ],
    inspector: [
      {
        binding: "value",
        label: "Label",
        domain: "how",
        page: { id: "badge", label: "Badge" },
        section: { id: "badge-text", label: "Text" },
        control: "text",
      },
      {
        binding: "stack",
        label: "Stack",
        domain: "where",
        page: { id: "badge", label: "Badge" },
        section: { id: "badge-stacking", label: "Stacking" },
        control: "number",
      },
    ],
    poster: { source: "surface-preview" },
    lane: { heightPx: 56, groupId: "clone-badges" },
  },
];

export default {
  format: "hypit.node-package@1" as const,
  hostFacets: [createStudioTrackCompanionHostFacet(badgeStudioCompanions)],
};
