# Import, export, and sharing

The **Files** menu imports notes or exports the current filtered collection. A note's
**Note actions** menu exports or shares that note, including a read-only note in Trash.
Pending edits must save successfully before an export is prepared. A failed save keeps
the edit available for retry.

Imports accept UTF-8 Markdown, UTF-8 plain text, and Duck Markdown ZIP packages.
An optional UTF-8 byte-order mark is removed from standalone Markdown/plain-text
documents. A leading U+FEFF in a Duck package remains note content. Import creates new note and image
identities and makes the new notes active and unpinned. It never replaces a note
because its title matches. Duplicate titles are reported because wiki links resolve
by title.

One note without attached images exports as `.md`. Collections and notes with images
export as ZIP packages. These contain readable Markdown and relative image files:

```text
manifest.json
notes/<note UUID>.md
assets/<image UUID>.<image extension>
```

The manifest is version 1 with kind `markdown`. Entry names derive from canonical
UUIDs, rather than supplied filesystem paths. Only parsed image destinations are
rewritten; references used as code examples stay unchanged. External image URLs are
kept as text and are not fetched. Missing local images, inconsistent ownership,
duplicate entries, malformed UTF-8, damaged ZIP entries, and unsupported metadata
are rejected. Generic ZIP files from other applications are not Duck packages;
their individual Markdown files can be imported separately. A standalone document
cannot supply sibling image files: parsed relative image references or private
`attachment:` references without their image bytes are rejected. Use a complete
Duck ZIP package for notes with local images. Literal examples inside code blocks
remain text.

## Limits

Sizes count actual streamed bytes, not a document provider's reported length.
Oversized input fails visibly; content is never silently omitted.

| Item | Limit |
| --- | --- |
| Notes in a collection | 10,000 |
| Images in a collection | 10,000 |
| UTF-8 text per imported note | 2 MiB |
| Total note text | 64 MiB |
| Archive and expanded contents | 1 GiB each |
| Individual image | 20 MiB, 100 megapixels, 32,768 pixels per side |
| Manifest | 16 MiB |
| ZIP entries | 20,001 |
| ZIP central directory | 8 MiB; ZIP64 and multi-disk archives are unsupported |
| Distinct tags in an import | 10,000 |
| Tag name | 256 UTF-16 code units, up to 32 path segments |

The note, image, total-text, and collection-size limits are also checked against
existing library contents before an import commits. Private staging requires
additional free space. Larger existing notes or tag structures can remain in the
library; an incompatible transfer fails instead of truncating them.

## Storage and failures

Preparation uses private storage excluded from Android backup. Cancelled and failed
preparations clean their staging directories. A new process removes abandoned stages.
If cleanup fails after an operation succeeds, Duck keeps the success result and
reports the cleanup problem separately, so an already imported collection is not
mistakenly reported as a failed import.
The database commit is atomic, and attachment recovery markers cover interrupted
file publication. Existing notes and images remain unchanged after a rejected import.

Exports use Android's document picker. Access permission can expire, a provider can
fail, and cancellation can interrupt a write. Duck attempts to remove a newly created
partial destination after failure and reports the failure. A provider can refuse
cleanup; check a failed export's destination before retrying.

Sharing uses a non-exported FileProvider scoped to prepared files in the export cache.
The receiving application receives temporary read access. Share copies are eligible
for removal on the next startup after 24 hours, and Android may evict them earlier.
No private database, credentials, or staging directory is exposed through this provider.

Returning from a picker passes through the ordinary app-lock gate before Duck reads
or writes the selected document. App lock controls access to the interface; it does
not encrypt the live database or exported Markdown.
