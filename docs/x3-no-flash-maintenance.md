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
**Text Anti-Aliasing** to **Off**, then hides both settings while the mode is
active. It also uses two fixed extra same-frame reinforcement passes after each
eligible page turn. This keeps the tested X3 configuration together as one
setting.

Text anti-aliasing must be off because anti-aliased text contains grayscale
pixels. Grayscale pages deliberately use the full-refresh cleanup path. A
manual full refresh can still be used whenever the remaining ghosting becomes
distracting. Switching **Screen Maintenance** back to **Full Refresh** makes
the frequency and anti-aliasing controls visible again.

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

The firmware keeps the conservative cleanup path for:

- pages containing images or grayscale, including anti-aliased text;
- indexing, bookmark, error, and other popup residue;
- wake and sleep transitions;
- manual full refreshes;
- 2-bit grayscale XTC pages; and
- non-X3 devices.

After an image or grayscale page, the next ordinary page can also receive a
full cleanup to remove residue left by that content. These flashes are expected
and do not mean the no-flash setting has stopped working.
