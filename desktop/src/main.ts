import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import { router } from "./router";
import { initTheme } from "./theme";
import { guardDrops } from "./dropGuard";
import "./tokens.css";
import "./components.css";
import "./styles.css";

initTheme();

guardDrops();

createApp(App).use(createPinia()).use(router).mount("#app");
