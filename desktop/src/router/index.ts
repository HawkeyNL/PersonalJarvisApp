import { createRouter, createWebHistory } from "vue-router";
import Home from "../views/Home.vue";
import Trading from "../views/Trading.vue";
import Health from "../views/Health.vue";
import Settings from "../views/Settings.vue";
import NodeStub from "../views/NodeStub.vue";
import Context from "../views/Context.vue";
import Conversations from "../views/Conversations.vue";
import Tasks from "../views/Tasks.vue";
import Integrations from "../views/Integrations.vue";
import Agents from "../views/Agents.vue";

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    // Core hub + module pages
    { path: "/", name: "home", component: Home },
    { path: "/conversations", name: "conversations", component: Conversations },
    { path: "/agents", name: "agents", component: Agents },
    { path: "/tasks", name: "tasks", component: Tasks },
    { path: "/integrations", name: "integrations", component: Integrations },
    { path: "/health", name: "health", component: Health },
    { path: "/memory", name: "memory", component: NodeStub },
    { path: "/context", name: "context", component: Context },
    { path: "/settings", name: "settings", component: Settings },

    // Trading desk — one component, sub-tab driven by the path
    { path: "/trading", name: "trading", component: Trading },
    { path: "/trading/ibkr", name: "trading-ibkr", component: Trading },

    // Legacy paths → new structure
    { path: "/status", redirect: "/health" },
    { path: "/portfolio", redirect: "/trading" },
    { path: "/broker", redirect: "/trading/ibkr" },
  ],
});
