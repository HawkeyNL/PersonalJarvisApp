<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { invoke } from "@tauri-apps/api/core";
import { getJsonAuth } from "../api";
type Model = { provider: string; model: string; enabled: boolean; source: string; route?: string };
type Policy = { models: Model[]; mutation: string; policy_sha256: string | null };
const policy = ref<Policy | null>(null);
const busy = ref(false);
const notice = ref("");
const search = ref("");
const accessFilter = ref<"all" | "enabled" | "disabled">("all");
const page = ref(0);
const selected = ref<Model | null>(null);
const enabledCount = computed(() => policy.value?.models.filter(m => m.enabled).length ?? 0);
const matches = computed(() => policy.value?.models.filter(m =>
  (accessFilter.value === "all" || m.enabled === (accessFilter.value === "enabled"))
  && `${m.provider} ${m.model}`.toLowerCase().includes(search.value.toLowerCase()),
) ?? []);
const rows = computed(() => matches.value.slice(page.value * 25, page.value * 25 + 25));
const mutable = computed(() => policy.value?.mutation === "device-signed-model-toggle-v1" && !!policy.value.policy_sha256);
async function refresh() {
  policy.value = await getJsonAuth<Policy>("/v1/system/models");
  page.value = Math.min(page.value, Math.max(0, Math.ceil(matches.value.length / 25) - 1));
}
async function reload() {
  busy.value = true;
  try { await refresh(); notice.value = ""; }
  catch { notice.value = "Model policy unreachable. Check the connection and session."; policy.value = null; }
  finally { busy.value = false; }
}
async function confirm() {
  const model = selected.value;
  const hash = policy.value?.policy_sha256;
  selected.value = null;
  if (!model || !hash || busy.value) return;
  busy.value = true;
  try {
    await invoke("set_model_enabled", { provider: model.provider, model: model.model, enabled: !model.enabled, policySha256: hash });
    notice.value = "Model access verified and updated.";
  } catch (error) { notice.value = String(error); }
  finally {
    try { await refresh(); } catch { policy.value = null; notice.value += " Reloading the status failed."; }
    busy.value = false;
  }
}
onMounted(reload);
</script>

<template>
  <section class="model-controls">
    <h3>Model access</h3>
    <p>"Allowed" means Jarvis may choose this model, not that it is handling a request right now. A working provider and the existing budget are still required. OS authentication lasts five minutes, until you lock or sign out; you confirm every change separately.</p>
    <p v-if="policy" class="model-summary">{{ enabledCount }} allowed · {{ policy.models.length - enabledCount }} blocked</p>
    <div class="model-toolbar">
      <input v-model="search" aria-label="Search models" placeholder="Search provider or model" @input="page = 0" />
      <select v-model="accessFilter" aria-label="Filter model access" @change="page = 0">
        <option value="all">All models</option>
        <option value="enabled">Allowed</option>
        <option value="disabled">Blocked</option>
      </select>
      <button :disabled="busy" @click="reload">Refresh</button>
    </div>
    <p role="status">{{ notice }}</p>
    <p v-if="policy && !mutable">Model control is unavailable on this Core, or the active policy differs. Use the trusted CLI to repair it.</p>
    <ul>
      <li v-for="model in rows" :key="model.provider + '/' + model.model">
        <div><strong>{{ model.model }}</strong><small>{{ model.provider }}<template v-if="model.route"> · route {{ model.route }}</template></small></div>
        <span class="access-badge" :class="model.enabled ? 'access-enabled' : 'access-disabled'">{{ model.enabled ? "Allowed" : "Blocked" }}</span>
        <button :disabled="busy || !mutable" @click="selected = model">{{ model.enabled ? "Disable" : "Enable" }}</button>
      </li>
    </ul>
    <p v-if="policy && !matches.length">No models match this search or filter.</p>
    <div class="model-toolbar">
      <button :disabled="page === 0 || busy" @click="page--">Previous</button>
      <span>{{ page + 1 }} / {{ Math.max(1, Math.ceil(matches.length / 25)) }}</span>
      <button :disabled="(page + 1) * 25 >= matches.length || busy" @click="page++">Next</button>
    </div>
    <div v-if="selected" class="model-confirm" role="dialog" aria-modal="true" aria-label="Confirm model change" @keydown.esc="selected = null">
      <div>
        <h3>{{ selected.enabled ? "Revoke model access?" : "Allow model access?" }}</h3>
        <p>{{ selected.provider }} / {{ selected.model }}</p>
        <p>This changes the shared Home Node policy for all devices. It does not pick an active model for running requests, and those are not cancelled.</p>
        <button autofocus @click="selected = null">Cancel</button>
        <button @click="confirm">Confirm change</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.model-controls { width: 100%; min-width: 0; }
.model-controls p { color: var(--muted, #9eaba7); overflow-wrap: anywhere; }
.model-toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
input { flex: 1; min-width: 120px; padding: 10px; }
select { padding: 10px; min-width: 150px; }
ul { list-style: none; padding: 0; }
li { display: flex; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px solid #ffffff12; }
li div { flex: 1; min-width: 0; overflow-wrap: anywhere; }
small { display: block; color: #9eaba7; }
.model-summary { font-weight: 600; color: #d7efe1 !important; }
.access-badge { border: 1px solid; border-radius: 999px; padding: 5px 10px; font-size: .85rem; white-space: nowrap; }
.access-enabled { color: #8ce0ae; border-color: #43855a; background: #183626; }
.access-disabled { color: #aeb8b3; border-color: #53615a; background: #26312b; }
.model-confirm { position: fixed; inset: 0; z-index: 100; background: #000b; display: grid; place-items: center; padding: 20px; }
.model-confirm > div { max-width: 520px; background: #12201b; border: 1px solid #365447; padding: 24px; border-radius: 12px; }
button { padding: 8px 12px; }
</style>
