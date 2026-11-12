// Native file drop is off (tauri.conf.json) so the composer gets dropped
// photos. The window swallows every other drop: a file would open in the
// window, a dragged link would navigate it away. Text fields keep text drops.

/** Whether the window should cancel this dragover/drop. One the composer
 *  already handled (defaultPrevented) is left alone. */
export function blocksDrop(types: readonly string[], defaultPrevented: boolean, editable: boolean): boolean {
  if (defaultPrevented) return false;
  return types.includes("Files") || !editable;
}

/** Fields that take dropped text; a checkbox or a read-only field does not. */
export const TEXT_FIELDS =
  ":is(textarea, input:not([type]), input:is([type=text], [type=search], [type=url], [type=email])):not(:disabled, [readonly]), [contenteditable]:not([contenteditable=false])";
const editable = (target: EventTarget | null) => target instanceof Element && target.matches(TEXT_FIELDS);

export function guardDrops(): void {
  for (const kind of ["dragover", "drop"]) {
    window.addEventListener(kind, (event) => {
      const transfer = (event as DragEvent).dataTransfer;
      if (!blocksDrop([...(transfer?.types ?? [])], event.defaultPrevented, editable(event.target))) return;
      event.preventDefault();
      if (transfer) transfer.dropEffect = "none";
    });
  }
}
