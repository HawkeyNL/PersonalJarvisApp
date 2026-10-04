<script setup lang="ts">
import { ref, onMounted } from "vue";
import AccountAdministration from "../components/AccountAdministration.vue";
import JvBackdrop from "../components/jv/JvBackdrop.vue";
import JvTopBar from "../components/jv/JvTopBar.vue";
import { ACCENTS, PRESETS, currentAccent, applyAccent, type Accent } from "../theme";
import { currentAuthStatus, deregisterDevice, type AuthStatus } from "../auth";
import { configureHomeNode, homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import { lockEnabled, isDesktop, setLockEnabled, initLock } from "../lock";
import {
  voiceSupported,
  enrolled,
  engine,
  busy,
  voiceStatus,
  voiceError,
  lastVerify,
  refreshVoiceStatus,
  enroll,
  verify,
} from "../voiceServer";
import {
  wakeEnabled,
  wakeStatus,
  wakeError,
  wakeReady,
  setWakeEnabled,
} from "../voicewake";
import { mics, selectedMic, setMic, listMics } from "../micDevices";
import {
  availableAppVersion,
  checkForUpdate,
  currentAppVersion,
  installAvailableUpdate,
  loadUpdateStatus,
  restartAfterUpdate,
  updateBusy,
  updateError,
  updateNotes,
  updateProgress,
  updateState,
} from "../updates";

async function onEnroll() {
  await enroll();
  await listMics(); // labels become available once mic permission is granted
}

const accent = ref<Accent>(currentAccent());
const sections = ["General", "Account & security", "Voice", "Updates"] as const;
const section = ref<(typeof sections)[number]>("General");
const session = ref<AuthStatus | null>(null);
const homeNodeOriginInput = ref("");
const homeNodeSaving = ref(false);
const homeNodeMessage = ref<string | null>(null);

const labels: Record<Accent, string> = {
  green: "Jarvis green",
  cyan: "Cyan",
  amber: "Amber",
  violet: "Violet",
};

function pick(a: Accent) {
  accent.value = a;
  applyAccent(a);
}

const confirmUnlink = ref(false);
const unlinking = ref(false);

async function doUnlink() {
  unlinking.value = true;
  try {
    await deregisterDevice();
    session.value = await currentAuthStatus();
  } catch {
    homeNodeMessage.value = "Unlinking did not finish. Confirm on your device and try again.";
  } finally {
    unlinking.value = false;
    confirmUnlink.value = false;
  }
}

async function saveHomeNodeOrigin() {
  homeNodeSaving.value = true;
  homeNodeMessage.value = null;
  try {
    const value = await configureHomeNode(homeNodeOriginInput.value);
    homeNodeOriginInput.value = value.origin ?? "";
    homeNodeMessage.value = "Home Node origin saved.";
    await loadUpdateStatus();
  } catch (error) {
    homeNodeMessage.value = error instanceof Error ? error.message : String(error);
  } finally {
    homeNodeSaving.value = false;
  }
}

onMounted(async () => {
  session.value = await currentAuthStatus();
  const homeNode = await loadHomeNodeConfig();
  homeNodeOriginInput.value = homeNode.origin ?? "";
  await loadUpdateStatus();
  await initLock(); // resolves isDesktop for the lock toggle
  await refreshVoiceStatus();
  await listMics();
});
</script>

<template>
  <div class="settings-page">
  <JvBackdrop glow-y="20%" horizon="80px" />
  <JvTopBar variant="node" title="SETTINGS" subtitle="THIS DEVICE AND YOUR ACCOUNT" />
  <section class="view settings">
    <nav class="settings-sections" aria-label="Settings sections">
      <button v-for="item in sections" :key="item" :aria-pressed="section === item"
        :class="{ selected: section === item }" @click="section = item">{{ item }}</button>
    </nav>
    <AccountAdministration v-if="session?.authenticated && section === 'Account & security'" class="account-administration" />

    <div v-if="section === 'General'" class="panel glass">
      <div class="panel-head">HOME NODE <span class="hint">device configuration</span></div>
      <p class="muted small">
        This credential-free address is stored locally and used for all API requests and update discovery.
      </p>
      <label class="field-label" for="settings-home-node-origin">HTTPS origin</label>
      <div class="origin-row">
        <input
          id="settings-home-node-origin"
          v-model.trim="homeNodeOriginInput"
          type="url"
          inputmode="url"
          autocomplete="url"
          placeholder="https://jarvis.home.example"
        />
        <button class="ghost" :disabled="homeNodeSaving" @click="saveHomeNodeOrigin">
          {{ homeNodeSaving ? "Saving…" : "Save" }}
        </button>
      </div>
      <p v-if="homeNodeMessage" class="small muted" role="status">{{ homeNodeMessage }}</p>
    </div>

    <div v-if="section === 'General'" class="panel glass">
      <div class="panel-head">APPEARANCE <span class="hint">accent colour</span></div>
      <div class="swatches">
        <button
          v-for="a in ACCENTS"
          :key="a"
          class="swatch"
          :class="{ on: accent === a }"
          :style="{ '--c1': PRESETS[a][0], '--c2': PRESETS[a][1] }"
          @click="pick(a)"
        >
          <span class="chip-dot"></span>
          {{ labels[a] }}
        </button>
      </div>
      <p class="muted small">Green is the Jarvis default.</p>
    </div>

    <div v-if="section === 'Account & security'" class="panel glass">
      <div class="panel-head">ACCOUNT <span class="hint">device-bound</span></div>
      <ul class="kv">
        <li>
          <span class="k">Status</span>
          <span class="v">
            <span class="dot" :class="session?.authenticated ? 'dot-ok' : 'dot-todo'"></span>
            {{ session?.authenticated ? "signed in" : "signed out" }}
          </span>
        </li>
        <li>
          <span class="k">Device ID</span>
          <span class="v mono">{{ session?.device_id ?? "—" }}</span>
        </li>
        <li>
          <span class="k">Key</span>
          <span class="v">{{ session?.has_key ? "in keychain" : "none" }}</span>
        </li>
      </ul>
      <button
        v-if="session?.authenticated"
        class="ghost danger"
        @click="confirmUnlink = true"
      >
        Unlink this device
      </button>
    </div>

    <div v-if="section === 'Account & security'" class="panel glass">
      <div class="panel-head">SECURITY <span class="hint">app lock</span></div>
      <label class="toggle" :class="{ off: !isDesktop }">
        <span class="tl">
          <span class="tt">Lock on open</span>
          <span class="td">
            Touch ID / Face ID when Jarvis opens. If biometrics fail, you
            approve from your phone.
          </span>
        </span>
        <input
          type="checkbox"
          :checked="lockEnabled"
          :disabled="!isDesktop"
          @change="setLockEnabled(($event.target as HTMLInputElement).checked)"
        />
        <span class="sw"></span>
      </label>
      <p v-if="!isDesktop" class="muted small">
        This device approves unlocks for your desktop, so it needs no lock of its own.
      </p>
    </div>

    <div v-if="section === 'Voice'" class="panel glass full-width">
      <div class="panel-head">VOICE <span class="hint">server-side · central</span></div>
      <ul class="kv">
        <li>
          <span class="k">Voice profile</span>
          <span class="v">
            <span class="dot" :class="enrolled ? 'dot-ok' : 'dot-todo'"></span>
            {{ enrolled ? "enrolled" : "not yet" }}
          </span>
        </li>
        <li v-if="engine">
          <span class="k">Engine</span>
          <span class="v mono">{{ engine }}</span>
        </li>
      </ul>

      <label class="miclabel">
        <span class="fl">Microphone</span>
        <select
          class="micsel"
          :value="selectedMic"
          @change="setMic(($event.target as HTMLSelectElement).value)"
        >
          <option value="">System default</option>
          <option v-for="m in mics" :key="m.deviceId" :value="m.deviceId">{{ m.label }}</option>
        </select>
      </label>

      <div class="enroll">
        <button class="ghost" :disabled="!voiceSupported || busy" @click="onEnroll">
          {{ enrolled ? "Record voice again" : "Record your voice" }}
        </button>
        <button
          v-if="enrolled"
          class="ghost"
          :disabled="!voiceSupported || busy"
          @click="verify()"
        >
          Test verification
        </button>
      </div>

      <p v-if="!voiceSupported" class="small errc">
        Microphone unavailable on this device.
      </p>
      <p v-else-if="voiceStatus" class="small" :class="busy ? 'muted' : 'okc'">
        {{ voiceStatus }}
      </p>
      <p v-if="voiceError" class="small errc">{{ voiceError }}</p>

      <p v-if="lastVerify && lastVerify.enrolled && lastVerify.transcript" class="small muted">
        Heard: "{{ lastVerify.transcript }}"
      </p>

      <label class="toggle" :class="{ off: !wakeReady }" style="margin-top: 14px">
        <span class="tl">
          <span class="tt">Listen for "Hey Jarvis"</span>
          <span class="td">
            Works on every device (inside the app). Only your voice wakes Jarvis.
            Until auto-detection exists: ⌘⇧J as a test trigger.
          </span>
        </span>
        <input
          type="checkbox"
          :checked="wakeEnabled"
          :disabled="!wakeReady"
          @change="setWakeEnabled(($event.target as HTMLInputElement).checked)"
        />
        <span class="sw"></span>
      </label>
      <p class="small" :class="wakeError ? 'errc' : 'muted'">
        {{ wakeError || "status: " + wakeStatus }}
      </p>

      <p class="muted small">
        Your voice lives centrally on your own server and works on all your devices.
        Voice is convenience; Touch ID or your phone stays the lock.
      </p>
    </div>

    <div v-if="section === 'Updates'" class="panel glass full-width">
      <div class="panel-head">JARVIS APP <span class="hint">private updates</span></div>
      <ul class="kv">
        <li><span class="k">Version</span><span class="v mono">v{{ currentAppVersion }}</span></li>
        <li>
          <span class="k">Status</span>
          <span class="v">
            <span class="dot" :class="updateState === 'error' ? 'dot-err' : updateState === 'available' ? 'dot-todo' : 'dot-ok'"></span>
            <template v-if="updateState === 'checking'">checking…</template>
            <template v-else-if="updateState === 'downloading'">downloading{{ updateProgress === null ? '…' : ` · ${updateProgress}%` }}</template>
            <template v-else-if="updateState === 'installing'">installing…</template>
            <template v-else-if="updateState === 'available'">v{{ availableAppVersion }} available</template>
            <template v-else-if="updateState === 'installed'">installation ready</template>
            <template v-else-if="updateState === 'up_to_date'">up-to-date</template>
            <template v-else-if="updateState === 'ready'">ready to check</template>
            <template v-else-if="updateState === 'unauthenticated'">sign in to check</template>
            <template v-else-if="updateState === 'unconfigured'">Home Node not configured</template>
            <template v-else-if="updateState === 'unsupported'">not available in this build</template>
            <template v-else-if="updateState === 'incompatible'">update protocol not compatible</template>
            <template v-else-if="updateState === 'unavailable'">update service unreachable</template>
            <template v-else-if="updateState === 'error'">check failed</template>
            <template v-else>ready</template>
          </span>
        </li>
      </ul>
      <div v-if="updateState === 'downloading' || updateState === 'installing'" class="update-progress" aria-live="polite">
        <span :style="{ width: `${updateProgress ?? 15}%` }"></span>
      </div>
      <div class="update-actions">
        <button v-if="updateState === 'available'" class="ghost" :disabled="updateBusy" @click="installAvailableUpdate">
          Update now
        </button>
        <button v-else-if="updateState === 'installed'" class="ghost" @click="restartAfterUpdate">
          Restart Jarvis
        </button>
        <button v-else class="ghost" :disabled="updateBusy || updateState === 'unsupported'" @click="checkForUpdate">
          Check for updates
        </button>
      </div>
      <p v-if="updateError" class="small errc">{{ updateError }}</p>
      <p v-else-if="updateNotes && (updateState === 'incompatible' || updateState === 'unavailable')" class="small muted">
        {{ updateNotes }}
      </p>
      <p v-else class="muted small">
        Updates come through your paired Home Node and are verified cryptographically before installation.
      </p>
    </div>

    <div v-if="section === 'General'" class="panel glass full-width">
      <div class="panel-head">SYSTEM <span class="hint">info</span></div>
      <ul class="kv">
        <li><span class="k">Backend</span><span class="v mono">{{ homeNodeConfig.origin ?? "not configured" }}</span></li>
        <li><span class="k">Client</span><span class="v mono">Jarvis MK I · v{{ currentAppVersion || "…" }}</span></li>
        <li><span class="k">Broker</span><span class="v">IBKR read-only</span></li>
      </ul>
    </div>

    <!-- Destructive-action confirmation. -->
    <Transition name="modal">
      <div v-if="confirmUnlink" class="modal-overlay" @click.self="confirmUnlink = false">
        <div class="modal glass" role="dialog" aria-modal="true" aria-labelledby="unlink-title">
          <h2 id="unlink-title">Unlink this device?</h2>
          <p>
            This revokes the device on the server and erases its device key here.
            You then have to pair (enroll) this device again to sign in.
            Your data on the server stays.
          </p>
          <div class="modal-actions">
            <button class="ghost" :disabled="unlinking" @click="confirmUnlink = false">
              Cancel
            </button>
            <button class="ghost danger" :disabled="unlinking" @click="doUnlink">
              {{ unlinking ? "Unlinking…" : "Unlink" }}
            </button>
          </div>
        </div>
      </div>
    </Transition>
  </section>
  </div>
</template>

<style scoped>
.settings-page { position: relative; min-height: 100%; padding-bottom: 96px; }
.settings-page > .settings { position: relative; z-index: 1; margin: 0 auto; padding: 28px 24px 0; }
.settings { max-width: 1180px; display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 20px; align-items: start; overflow-wrap: anywhere; }
.settings-sections, .full-width, .account-administration { grid-column: 1 / -1; }
.settings-sections { display: flex; flex-wrap: wrap; gap: 8px; }
.settings-sections button { background: rgba(3, 24, 19, 0.8); border: 1px solid var(--line-a30); color: var(--text-3); font-weight: 400; border-radius: var(--r-12); }
.settings-sections button.selected { color: #f8fffb; border-color: var(--accent); background: rgba(var(--accent-rgb), 0.1); box-shadow: 0 0 14px rgba(var(--accent-rgb), 0.35); }
.settings-sections button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
@media (max-width: 760px) { .settings { grid-template-columns: minmax(0,1fr); } }
.panel {
  min-width: 0;
  position: relative;
  border: 1px solid var(--line-a45);
  border-radius: var(--r-22);
  padding: 18px 20px 20px;
  margin-bottom: 0;
}
.glass {
  background: var(--panel-bg);
  box-shadow: 0 0 22px rgba(var(--accent-rgb), 0.12);
}
.panel-head {
  display: flex; align-items: center; justify-content: space-between;
  font-size: 11px; letter-spacing: 0.3em;
  color: var(--text-5); margin-bottom: 14px;
}
.hint { font-size: 9px; color: var(--muted); letter-spacing: 0.1em; }

.swatches { display: flex; flex-wrap: wrap; gap: 10px; }
.swatch {
  display: inline-flex; align-items: center; gap: 8px;
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid var(--border); color: var(--text);
  border-radius: 999px; padding: 7px 14px; font-size: 13px; cursor: pointer;
  font-weight: 500;
}
.swatch .chip-dot {
  width: 12px; height: 12px; border-radius: 50%;
  background: linear-gradient(135deg, var(--c1), var(--c2));
  box-shadow: 0 0 8px var(--c1);
}
.swatch.on { border-color: var(--c1); box-shadow: 0 0 0 1px var(--c1), 0 0 14px rgba(255,255,255,0.06); }
.swatch:hover { border-color: var(--c1); }

.small { font-size: 12px; margin: 12px 0 0; }

.kv { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.kv li { display: flex; align-items: center; gap: 12px; }
.kv .k { font-size: 13px; color: var(--muted); }
.kv .v { margin-left: auto; font-size: 13px; display: inline-flex; align-items: center; gap: 8px; }
.mono { font-family: var(--mono); font-size: 12px; }
.ghost {
  margin-top: 14px; background: transparent; border: 1px solid var(--border);
  color: var(--text-3); font-size: 12px; font-weight: 400;
}
.ghost:hover { color: var(--accent-2); border-color: var(--accent-2); filter: none; }
.ghost.danger { color: #f87171; border-color: rgba(248, 113, 113, 0.4); }
.ghost.danger:hover { color: #fca5a5; border-color: #f87171; }
.ghost:disabled { opacity: 0.5; cursor: default; }

/* Destructive confirmation modal. */
.modal-overlay {
  position: fixed; inset: 0; z-index: 50;
  display: flex; align-items: center; justify-content: center; padding: 20px;
  background: rgba(1, 9, 10, 0.82);
}
.modal {
  width: min(420px, 100%);
  border: 1px solid var(--border); border-radius: 14px; padding: 20px;
  box-shadow: 0 24px 70px rgba(0, 0, 0, 0.6);
}
.modal h2 { margin: 0 0 10px; font-size: 16px; }
.modal p { margin: 0; font-size: 13px; color: var(--muted); line-height: 1.5; }
.modal-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 18px; }
.modal-actions .ghost { margin-top: 0; }

.modal-enter-active, .modal-leave-active { transition: opacity 0.2s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
.modal-enter-active .modal, .modal-leave-active .modal { transition: transform 0.2s ease; }
.modal-enter-from .modal, .modal-leave-to .modal { transform: translateY(8px) scale(0.98); }

.toggle { display: flex; align-items: center; gap: 14px; cursor: pointer; }
.toggle.off { cursor: default; }
.toggle .tl { display: flex; flex-direction: column; gap: 3px; }
.tt { font-size: 14px; }
.td { font-size: 12px; color: var(--muted); line-height: 1.4; max-width: 42ch; }
.toggle input { position: absolute; opacity: 0; pointer-events: none; }
.sw {
  margin-left: auto; flex: none; position: relative;
  width: 46px; height: 27px; border-radius: 999px;
  background: rgba(255, 255, 255, 0.08); border: 1px solid var(--border);
  transition: background 0.2s ease, border-color 0.2s ease;
}
.sw::after {
  content: ""; position: absolute; top: 2px; left: 2px;
  width: 21px; height: 21px; border-radius: 50%;
  background: var(--muted); transition: transform 0.2s ease, background 0.2s ease;
}
.toggle input:checked ~ .sw { background: rgba(var(--accent-rgb), 0.25); border-color: var(--accent); }
.toggle input:checked ~ .sw::after { transform: translateX(19px); background: var(--accent); }
.toggle input:disabled ~ .sw { opacity: 0.5; }

.enroll { display: flex; align-items: center; gap: 12px; margin-top: 12px; flex-wrap: wrap; }
.miclabel { display: flex; flex-direction: column; gap: 6px; margin-top: 12px; }
.fl { font-size: 13px; color: var(--muted); }
.micsel {
  background: rgba(0, 0, 0, 0.25); border: 1px solid var(--border); color: var(--text);
  border-radius: 10px; padding: 8px 10px; font: inherit; font-size: 13px;
}
.micsel:focus { outline: none; border-color: var(--accent); }
.enroll .ghost { margin-top: 0; }
.update-actions { display: flex; gap: 10px; flex-wrap: wrap; }
.origin-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.origin-row input { flex: 1 1 20rem; min-width: 0; }
.origin-row .ghost { margin: 0; }
.field-label { display: block; margin: 0.75rem 0 0.4rem; }
.update-actions .ghost { margin-top: 14px; }
.update-progress {
  height: 6px; margin-top: 14px; overflow: hidden; border-radius: 999px;
  background: rgba(255, 255, 255, 0.08);
}
.update-progress span {
  display: block; height: 100%; min-width: 8px; border-radius: inherit;
  background: var(--accent); transition: width 0.2s ease;
}
.okc { color: var(--accent); }
.errc { color: #f87171; }
</style>
