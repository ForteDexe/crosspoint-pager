# X3 no-flash screen maintenance

Xteink X3 can periodically reinforce unchanged black and white pixels during
the page turn itself. This reduces accumulated ghosting without adding a
flashing full-screen cleanup after the page has changed.

Some faint ghosting is normal. The reinforcement waveform is gentler than a
full refresh and is intended to reduce residue, not remove every trace of it.

## Configure it

No-flash maintenance is opt-in. On X3, select
**Settings > Display > Screen Maintenance > No Flash (X3)**.

Selecting this mode automatically sets **Refresh Frequency** to **1 page** and
hides that setting while the mode is active. It also uses two fixed extra
same-frame reinforcement passes after each eligible page turn. This keeps the
tested X3 maintenance cadence together as one setting.

**Text Anti-Aliasing** remains available. On text-only EPUB and TXT pages, No
Flash keeps dense font-stroke pixels black and uses gray only for lighter edge
pixels. This lets the existing black-and-white reinforcement maintain the text
without forcing a flashing cleanup. While No Flash is selected, two additional
controls are visible:

- **Gray Page Refresh > Fast Refresh** uses one ordinary fast update when
  leaving grayscale content. It is the fastest option and may leave more gray
  residue.
- **Gray Page Refresh > Quick Refresh** uses the X3 grayscale-aware update for
  a gray page and the existing three-pass No Flash reinforcement when the next
  black-and-white page is shown. It takes longer but should settle more residue
  without a black full-refresh flash.
- **Force Gray to Black/White** skips the grayscale overlay and displays the
  page's black-and-white base instead. This removes gray tones and avoids the
  grayscale transition entirely.

**Quick Refresh** is the default grayscale policy; **Force Gray to
Black/White** defaults to Off. A manual full refresh can still be used whenever
the remaining ghosting becomes distracting. Switching **Screen Maintenance**
back to **Full Refresh** hides the two No Flash grayscale controls and makes
the frequency control visible again.

## Verify it

1. Select **No Flash (X3)**.
2. Open an already-indexed, text-only EPUB or TXT file with no images.
3. Turn forward through several pages. Opening the book or drawing its first
   page may still use a full refresh; eligible page turns after that should be
   one continuous transition without a separate full-screen black/white flash.
4. Change only **Screen Maintenance** to **Full Refresh** and repeat the same
   page turns. At the one-page interval, the cleanup flash should now be
   obvious. This A/B comparison is the simplest confirmation that no-flash
   maintenance is active.

## When a full refresh is still expected

The firmware keeps the conservative cleanup path outside X3 No Flash for:

- pages containing images or other grayscale content;
- indexing, bookmark, error, and other popup residue;
- wake and sleep transitions that require controller cleanup;
- manual full refreshes;
- 2-bit grayscale XTC pages; and
- non-X3 devices.

In X3 No Flash mode, the selected **Gray Page Refresh** policy replaces the
automatic black cleanup after EPUB images and for 2-bit grayscale XTC page
turns. Manual refreshes and unrelated safety cleanups can still flash.
