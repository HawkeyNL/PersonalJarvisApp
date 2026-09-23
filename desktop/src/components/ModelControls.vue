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
const page = ref(0);
const selected = ref<Model | null>(null);
const matches = computed(() => policy.value?.models.filter(m => `${m.provider} ${m.model}`.toLowerCase().includes(search.value.toLowerCase())) ?? []);
const rows = computed(() => matches.value.slice(page.value * 25, page.value * 25 + 25));
const mutable = computed(() => policy.value?.mutation === "device-signed-model-toggle-v1" && !!policy.value.policy_sha256);
async function refresh() {
  policy.value = await getJsonAuth<Policy>("/v1/system/models");
  page.value = Math.min(page.value, Math.max(0, Math.ceil(matches.value.length / 25) - 1));
}
async function reload() {
  busy.value = true;
  try { await refresh(); notice.value = ""; }
  catch { notice.value = "Modelpolicy niet bereikbaar. Controleer de verbinding en sessie."; policy.value = null; }
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
    notice.value = "Modelwijziging geverifieerd en actief.";
  } catch (error) { notice.value = String(error); }
  finally {
    try { await refresh(); } catch { policy.value = null; notice.value += " Status opnieuw ophalen mislukt."; }
    busy.value = false;
  }
}
onMounted(reload);
</script>

<template>
  <section class="model-controls">
    <h3>Modeltoegang</h3>
    <p>Inschakelen staat gebruik toe binnen het bestaande budget. Iedere wijziging vereist bevestiging via het besturingssysteem.</p>
    <div class="model-toolbar">
      <input v-model="search" aria-label="Zoek modellen" placeholder="Zoek provider of model" @input="page = 0" />
      <button :disabled="busy" @click="reload">Ververs</button>
    </div>
    <p role="status">{{ notice }}</p>
    <p v-if="policy && !mutable">Modelbediening is niet beschikbaar op deze Core of de actieve policy wijkt af. Gebruik de vertrouwde CLI voor herstel.</p>
    <ul>
      <li v-for="model in rows" :key="model.provider + '/' + model.model">
        <div><strong>{{ model.model }}</strong><small>{{ model.provider }}<template v-if="model.route"> · route {{ model.route }}</template></small></div>
        <span>{{ model.enabled ? "Aan" : "Uit" }}</span>
        <button :disabled="busy || !mutable" @click="selected = model">{{ model.enabled ? "Uitschakelen" : "Inschakelen" }}</button>
      </li>
    </ul>
    <p v-if="policy && !matches.length">Geen modellen gevonden.</p>
    <div class="model-toolbar">
      <button :disabled="page === 0 || busy" @click="page--">Vorige</button>
      <span>{{ page + 1 }} / {{ Math.max(1, Math.ceil(matches.length / 25)) }}</span>
      <button :disabled="(page + 1) * 25 >= matches.length || busy" @click="page++">Volgende</button>
    </div>
    <div v-if="selected" class="model-confirm" role="dialog" aria-modal="true" aria-label="Modelwijziging bevestigen" @keydown.esc="selected = null">
      <div>
        <h3>{{ selected.enabled ? "Model uitschakelen?" : "Model inschakelen?" }}</h3>
        <p>{{ selected.provider }} / {{ selected.model }}</p>
        <p>Dit wijzigt de gedeelde Home Node-policy voor alle apparaten. Lopende requests worden niet geannuleerd.</p>
        <button autofocus @click="selected = null">Annuleren</button>
        <button @click="confirm">Bevestigen met OS-authenticatie</button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.model-controls { width: 100%; min-width: 0; }
.model-controls p { color: var(--muted, #9eaba7); overflow-wrap: anywhere; }
.model-toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
input { flex: 1; min-width: 120px; padding: 10px; }
ul { list-style: none; padding: 0; }
li { display: flex; align-items: center; gap: 16px; padding: 12px 0; border-bottom: 1px solid #ffffff12; }
li div { flex: 1; min-width: 0; overflow-wrap: anywhere; }
small { display: block; color: #9eaba7; }
.model-confirm { position: fixed; inset: 0; z-index: 100; background: #000b; display: grid; place-items: center; padding: 20px; }
.model-confirm > div { max-width: 520px; background: #12201b; border: 1px solid #365447; padding: 24px; border-radius: 12px; }
button { padding: 8px 12px; }
</style>
