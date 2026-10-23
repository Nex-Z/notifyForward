---
name: NotifyForward
description: iOS-inspired grouped native Android interface
colors:
  brand_primary: "#007AFF"
  brand_container: "#E9F1FF"
  page_bg: "#F5F5F7"
  surface: "#FFFFFF"
  surface_alt: "#F2F2F7"
  field_bg: "#EDEDF0"
  text_primary: "#1D1D1F"
  text_secondary: "#8E8E93"
  divider: "#E5E5EA"
  accent: "#34C759"
  warning: "#FF9500"
  danger: "#FF3B30"
  purple: "#AF52DE"
  teal: "#30B0C7"
typography:
  screen-title:
    fontFamily: sans-serif
    fontSize: 28sp
    fontWeight: 700
    letterSpacing: -0.02em
  row-title:
    fontFamily: sans-serif
    fontSize: 16sp
  body:
    fontFamily: sans-serif
    fontSize: 14sp
  caption:
    fontFamily: sans-serif
    fontSize: 12sp
  section:
    fontFamily: sans-serif
    fontSize: 13sp
  nav-label:
    fontFamily: sans-serif
    fontSize: 10sp
rounded:
  card: 14dp
  stat: 18dp
  search: 12dp
  chip: 16dp
spacing:
  space_1: 4dp
  space_2: 8dp
  space_3: 12dp
  space_4: 16dp
  space_5: 20dp
  space_6: 24dp
components:
  card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.card}"
  stat-card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.stat}"
  search:
    backgroundColor: "{colors.field_bg}"
    rounded: "{rounded.search}"
    height: 44dp
  chip:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text_primary}"
    rounded: "{rounded.chip}"
    height: 32dp
  chip-selected:
    backgroundColor: "{colors.brand_primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.chip}"
    height: 32dp
---

# Design System: NotifyForward

## Overview

Retain the app's grouped-list visual world and refine it toward the user's iOS reference while preserving native Android behavior. The interface is quiet, compact and readable: large left-aligned titles, light grouped surfaces, restrained separators and blue interactions. This is an implementation record, not a new brand or audience definition; PRODUCT.md is absent.

Source of truth: `app/src/main/res/values/colors.xml`, `dimens.xml`, `themes.xml`, their night resources, and the layout/drawable/color resources. Update this document and `.impeccable/design.json` when those shared decisions change. Android dimensions remain dp and scalable type remains sp; these are not web pixel tokens.

## Colors

Primary: system blue identifies actions, selected filters, selected navigation and enabled switches. Neutral: pale page background supports white grouped surfaces; primary text carries content and secondary text carries context. Dividers remain subtle. Green, orange, red, purple and teal identify statuses, effect icons and chart series rather than filling entire screens.

Night mode resolves the same resource names through `values-night/colors.xml`. Use resource references instead of copying light hex values into layouts or drawing code. The prescribed light palette is intentional; no WCAG conformance claim is made.

## Typography

Use Android sans-serif with the frontmatter hierarchy. Screen titles are bold with tight tracking; row titles and body copy carry content, captions carry timestamps and secondary explanations. Section labels are sentence case. Navigation uses its smaller dedicated label style. Preserve font scaling; do not substitute fixed dp text sizes.

## Layout

Use the shared page margin (`space_5`) and four-dp spacing rhythm. Settings rows have a 56dp minimum height, 16dp horizontal padding and 12dp vertical padding; content may grow. Main screens scroll above the persistent bottom navigation. Preserve system insets and native controls.

The reviewed implementation covers history, rules, statistics and settings in light and dark mode at one phone size with normal font scaling. Larger fonts and other device sizes require separate verification.

## Elevation & Depth

Shared cards have zero elevation and no stroke. Tonal separation, grouping and whitespace provide depth. Pressed feedback uses native ripple/selectable backgrounds. Do not add decorative shadows to the shared cards.

## Shapes

Use the card radius for grouped containers and the larger stat radius for metric cards. Search fields have their own softer rectangular radius; filter chips are capsules. Dialogs use the same radius as stat cards. Colored icon tiles provide compact visual anchors within rows.

## Components

- History: search, horizontal filter chips and dated groups of notification rows. Keep metadata subordinate to notification content.
- Rules: grouped rows, effect icons and blue enabled switches. Switch styling uses a 51dp minimum width and a 48dp minimum control height; do not confuse the visual track with its control bounds.
- Statistics: key metric cards followed by charts and ranked app/rule rows. Use semantic chart colors and quiet tracks.
- Settings: section captions and grouped native rows with leading icons, trailing controls or disclosure indicators.
- Chips: selected state uses blue fill and white text; unselected state uses the surface and primary text. The visible chip height is compact and does not establish an accessibility certification.
- Navigation: surface background, blue selected icon/text, secondary inactive icon/text; the Material active pill is suppressed.

## Do's and Don'ts

- Do reuse named resources and existing widget/text styles across all four screens.
- Do maintain grouped hierarchy and use blue consistently for interaction.
- Do review both themes after shared resource changes, including chart labels and custom drawings.
- Don't replace the prescribed palette or increase decoration to manufacture hierarchy.
- Don't treat the one-device screenshot review as coverage for every density, font scale or accessibility requirement.
