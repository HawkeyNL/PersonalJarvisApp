import { createRouter, createWebHistory } from "vue-router";
import Home from "../views/Home.vue";
import Trading from "../views/Trading.vue";
import TradingDesk from "../views/TradingDesk.vue";
import TradingStocks from "../views/TradingStocks.vue";
import TradingStock from "../views/TradingStock.vue";
import TradingPositions from "../views/TradingPositions.vue";
import TradingMemecoin from "../views/TradingMemecoin.vue";
import Health from "../views/Health.vue";
import Settings from "../views/Settings.vue";
import Context from "../views/Context.vue";
import Tasks from "../views/Tasks.vue";
import Integrations from "../views/Integrations.vue";
import Agents from "../views/Agents.vue";
import Memory from "../views/Memory.vue";
import ComingSoon from "../views/ComingSoon.vue";
import { buildRoutes } from "../navigation";
import { currentAuthStatus } from "../auth";
import { loadHomeNodeConfig } from "../homeNode";
import { shellGuard } from "../shellMode";

// Paths, sidebar mapping and redirects live in navigation.ts.
export const router = createRouter({
  history: createWebHistory(),
  routes: buildRoutes({
    home: Home, agents: Agents, integrations: Integrations, health: Health,
    memory: Memory, trading: Trading, desk: TradingDesk, stocks: TradingStocks, stock: TradingStock, positions: TradingPositions, memecoin: TradingMemecoin, tasks: Tasks, context: Context, settings: Settings, soon: ComingSoon,
  }),
});

// No app page, deep link or reload lands past the setup and sign-in screen.
router.beforeEach(shellGuard(async () => ({
  configured: (await loadHomeNodeConfig()).configured,
  authenticated: (await currentAuthStatus()).authenticated,
})));
