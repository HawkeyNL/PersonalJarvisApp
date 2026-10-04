// Fit a fixed-size design (the hub and node page stages) into the space the
// window leaves for it. CSS cannot turn a container size into the unitless
// factor `transform: scale()` needs, so the factor is measured.
import { onBeforeUnmount, ref, watch, type Ref } from "vue";
import { fitScale } from "./hubModel";

/** Scale for `content` (laid out at its design size) inside `room`. Both refs
 *  may switch elements (v-if); the observer follows them. */
export function useFitScale(room: Ref<HTMLElement | null>, content: Ref<HTMLElement | null>, max: () => number): Ref<number> {
  const scale = ref(1);
  const update = () => {
    if (!room.value || !content.value) return;
    scale.value = fitScale(
      { width: room.value.clientWidth, height: room.value.clientHeight },
      { width: content.value.offsetWidth, height: content.value.offsetHeight },
      max(),
    );
  };
  const observer = new ResizeObserver(update);
  watch([room, content], ([r, c]) => {
    observer.disconnect();
    if (r) observer.observe(r);
    if (c) observer.observe(c);
    update();
  }, { flush: "post" });
  onBeforeUnmount(() => observer.disconnect());
  return scale;
}
