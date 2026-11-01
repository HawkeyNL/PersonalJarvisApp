import { createRouter, createWebHistory } from "vue-router";
import Home from "../views/Home.vue";
import Trading from "../views/Trading.vue";
import Health from "../views/Health.vue";
import Settings from "../views/Settings.vue";
import Context from "../views/Context.vue";
import Conversations from "../views/Conversations.vue";
import Tasks from "../views/Tasks.vue";
import Integrations from "../views/Integrations.vue";
import Agents from "../views/Agents.vue";
import Memory from "../views/Memory.vue";
import ComingSoon from "../views/ComingSoon.vue";
import { buildRoutes } from "../navigation";

// Paths, sidebar mapping and redirects live in navigation.ts.
export const router = createRouter({
  history: createWebHistory(),
  routes: buildRoutes({
    home: Home, conversations: Conversations, agents: Agents, integrations: Integrations, health: Health,
    memory: Memory, trading: Trading, tasks: Tasks, context: Context, settings: Settings, soon: ComingSoon,
  }),
});
