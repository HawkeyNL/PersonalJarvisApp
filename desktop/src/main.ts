import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { router } from "./router";
import { initTheme } from "./theme";
import "./tokens.css";
import "./components.css";
import "./styles.css";

initTheme();

// Native file drop is off (tauri.conf.json) so the composer gets dropped
// photos; a drop anywhere else must not open the file in the window.
for (const kind of ["dragover", "drop"]) {
  window.addEventListener(kind, (event) => {
    const transfer = (event as DragEvent).dataTransfer;
    if (event.defaultPrevented || !transfer?.types.includes("Files")) return;
    event.preventDefault();
    transfer.dropEffect = "none";
  });
}

createApp(App).use(createPinia()).use(router).mount("#app");
