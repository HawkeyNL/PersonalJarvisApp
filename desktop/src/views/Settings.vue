<script setup lang="ts">
import { computed, ref, onBeforeUnmount, onMounted, watch } from "vue";
import AccountAdministration from "../components/AccountAdministration.vue";
import PageShell from "../components/PageShell.vue";
import ThemeCards from "../components/settings/ThemeCards.vue";
import { currentTheme, setTheme, type ThemeChoice } from "../theme";
import { currentAuthStatus, currentShellMode, deregisterDevice, type AuthStatus } from "../auth";
import { configureHomeNode, homeNodeConfig, loadHomeNodeConfig } from "../homeNode";
import { lockEnabled, lockAvailable, lockMode, setLockEnabled } from "../lock";
import { initPlatformCapabilities, platformCapabilities } from "../platform";
import { useAppStore } from "../stores/app";
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
  lastUpdateCheck,
  updateProgress,
  updateState,
} from "../updates";

async function onEnroll() {
  await enroll();
  await listMics(); // labels become available once mic permission is granted
}

const theme = ref<ThemeChoice>(currentTheme());
const sections = ["Appearance", "Home Node", "Account & security", "Voice", "Updates"] as const;
const section = ref<(typeof sections)[number]>("Appearance");
const session = ref<AuthStatus | null>(null);
const homeNodeOriginInput = ref("");
const homeNodeSaving = ref(false);
const homeNodeMessage = ref<string | null>(null);

// "Saved" badge: shown briefly after a setting is stored.
const saved = ref(false);
let savedTimer: ReturnType<typeof setTimeout> | undefined;
function flashSaved() {
  clearTimeout(savedTimer);
  saved.value = true;
  savedTimer = setTimeout(() => { saved.value = false; }, 1600);
}
onBeforeUnmount(() => clearTimeout(savedTimer));

function pickTheme(t: ThemeChoice) {
  if (t === theme.value) return;
  theme.value = t;
  setTheme(t);
  flashSaved();
}

const confirmUnlink = ref(false);
// A Mac revokes itself with a Touch ID approval; other desktops only sign out.
const signer = computed(() => platformCapabilities.value.supportsBiometricSigning);
const unlinking = ref(false);
const unlinkMessage = ref<string | null>(null);
const app = useAppStore();

// Unlink confirmation: a native modal dialog (focus trap, Esc, inert page).
const unlinkDialog = ref<HTMLDialogElement | null>(null);
watch(confirmUnlink, (open) => {
  if (open && !unlinkDialog.value?.open) unlinkDialog.value?.showModal();
  else if (!open && unlinkDialog.value?.open) unlinkDialog.value.close();
}, { flush: "post" }); // after render, so showModal() finds the autofocus button
// Esc must not dismiss the dialog while unlinking is in flight.
function onUnlinkCancel(event: Event) {
  if (unlinking.value) event.preventDefault();
}

async function doUnlink() {
  if (unlinking.value) return;
  unlinking.value = true;
  unlinkMessage.value = null;
  const deviceId = session.value?.device_id;
  try {
    await deregisterDevice();
    session.value = await currentAuthStatus().catch(() => null);
    // Off macOS the device stays active until revoked on the Home Node. The
    // sign-in screen replaces this page, so the hint travels with the app store.
    if (!signer.value && deviceId) app.notice = `Unlinked. Revoke it on the Home Node: sudo jarvis devices revoke ${deviceId}`;
  } catch {
    unlinkMessage.value = "Unlinking did not finish. Try again.";
    // Signing out may already have replaced this page with the sign-in screen.
    if (currentShellMode.value !== "app") app.notice = unlinkMessage.value;
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
    // A new origin drops the session natively; leave the app if it did.
    await currentAuthStatus().catch(() => {});
    homeNodeOriginInput.value = value.origin ?? "";
    homeNodeMessage.value = "Home Node origin saved.";
    flashSaved();
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
  await initPlatformCapabilities(); // resolves lockAvailable for the lock toggle
  await refreshVoiceStatus();
  await listMics();
});
</script>

<template>
  <PageShell class="settings-page" title="Settings" subtitle="This device and your account">
    <template #actions>
      <span role="status" aria-live="polite">
        <span v-if="saved" class="badge pos"><span class="dot"></span>Saved</span>
      </span>
    </template>

    <div class="layout">
      <nav class="sections" aria-label="Settings sections">
        <button v-for="item in sections" :key="item" type="button" class="navlink"
          :aria-current="section === item ? 'true' : undefined" @click="section = item">{{ item }}</button>
      </nav>

      <div class="content">
        <ThemeCards v-if="section === 'Appearance'" :model-value="theme" @update:model-value="pickTheme" />

        <template v-if="section === 'Home Node'">
          <section class="card col" aria-labelledby="node-heading">
            <div class="head">
              <h2 id="node-heading">Home Node</h2>
              <p class="t-sm t-2">This credential-free address is stored locally and used for all API requests and update discovery.</p>
            </div>
            <div class="field">
              <label class="label" for="settings-home-node-origin">HTTPS origin</label>
              <div class="origin-row">
                <input
                  id="settings-home-node-origin"
                  v-model.trim="homeNodeOriginInput"
                  class="input"
                  type="url"
                  inputmode="url"
                  autocomplete="url"
                  placeholder="https://jarvis.home.example"
                />
                <button type="button" class="btn btn-secondary" :disabled="homeNodeSaving" @click="saveHomeNodeOrigin">
                  {{ homeNodeSaving ? "Saving…" : "Save" }}
                </button>
              </div>
            </div>
            <p v-if="homeNodeMessage" class="t-sm t-2 wrap" role="status">{{ homeNodeMessage }}</p>
          </section>

          <section class="card col" aria-labelledby="about-heading">
            <h2 id="about-heading">About</h2>
            <ul class="kv">
              <li><span class="k">Backend</span><span class="v mono">{{ homeNodeConfig.origin ?? "not configured" }}</span></li>
              <li><span class="k">Client</span><span class="v mono">Jarvis MK I · v{{ currentAppVersion || "…" }}</span></li>
              <li><span class="k">Broker</span><span class="v">IBKR read-only</span></li>
            </ul>
          </section>
        </template>

        <template v-if="section === 'Account & security'">
          <section class="card col" aria-labelledby="account-heading">
            <div class="head">
              <h2 id="account-heading">Account</h2>
              <p class="t-sm t-2">Sign-in is bound to this device's key.</p>
            </div>
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
            <div v-if="session?.authenticated">
              <button type="button" class="btn btn-danger-quiet" @click="confirmUnlink = true">Unlink this device</button>
            </div>
            <p v-if="unlinkMessage" class="t-sm t-2 wrap" role="status">{{ unlinkMessage }}</p>
          </section>

          <section class="card col" aria-labelledby="lock-heading">
            <h2 id="lock-heading">App lock</h2>
            <label class="toggle" :class="{ off: !lockAvailable }">
              <span class="tl">
                <span>Lock on open</span>
                <span class="t-xs t-2">
                  When Jarvis opens and after 15 minutes without input: Touch ID or
                  your computer password. It hides the app; it signs nothing.
                </span>
              </span>
              <input
                type="checkbox"
                class="switch"
                :checked="lockEnabled || lockMode === 'password'"
                :disabled="!lockAvailable"
                @change="setLockEnabled(($event.target as HTMLInputElement).checked); flashSaved()"
              />
            </label>
            <p v-if="lockMode === 'password'" class="t-sm t-2">
              On Windows, Jarvis signs out when it opens and after 15 minutes without
              input. Signing in again needs your account password.
            </p>
            <p v-else-if="!lockAvailable" class="t-sm t-2">
              This computer has no local unlock prompt, so the app lock is off.
            </p>
          </section>

          <AccountAdministration v-if="session?.authenticated" />
        </template>

        <section v-if="section === 'Voice'" class="card col" aria-labelledby="voice-heading">
          <div class="head">
            <h2 id="voice-heading">Voice</h2>
            <p class="t-sm t-2">Server-side, shared by all your devices.</p>
          </div>
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

          <div class="field">
            <label class="label" for="settings-mic">Microphone</label>
            <select
              id="settings-mic"
              class="input"
              :value="selectedMic"
              @change="setMic(($event.target as HTMLSelectElement).value); flashSaved()"
            >
              <option value="">System default</option>
              <option v-for="m in mics" :key="m.deviceId" :value="m.deviceId">{{ m.label }}</option>
            </select>
          </div>

          <div class="actions">
            <button type="button" class="btn btn-secondary" :disabled="!voiceSupported || busy" @click="onEnroll">
              {{ enrolled ? "Record voice again" : "Record your voice" }}
            </button>
            <button
              v-if="enrolled"
              type="button"
              class="btn btn-secondary"
              :disabled="!voiceSupported || busy"
              @click="verify()"
            >
              Test verification
            </button>
          </div>

          <p v-if="!voiceSupported" class="t-sm errc">
            Microphone unavailable on this device.
          </p>
          <p v-else-if="voiceStatus" class="t-sm" :class="busy ? 't-2' : 'okc'">
            {{ voiceStatus }}
          </p>
          <p v-if="voiceError" class="t-sm errc">{{ voiceError }}</p>

          <p v-if="lastVerify && lastVerify.enrolled && lastVerify.transcript" class="t-sm t-2">
            Heard: "{{ lastVerify.transcript }}"
          </p>

          <label class="toggle" :class="{ off: !wakeReady }">
            <span class="tl">
              <span>Listen for "Hey Jarvis"</span>
              <span class="t-xs t-2">
                Works on every device (inside the app). Only your voice wakes Jarvis.
                Until auto-detection exists: ⌘⇧J as a test trigger.
              </span>
            </span>
            <input
              type="checkbox"
              class="switch"
              :checked="wakeEnabled"
              :disabled="!wakeReady"
              @change="setWakeEnabled(($event.target as HTMLInputElement).checked); flashSaved()"
            />
          </label>
          <p class="t-sm" :class="wakeError ? 'errc' : 't-2'">
            {{ wakeError || "status: " + wakeStatus }}
          </p>

          <p class="t-sm t-2">
            Your voice lives centrally on your own server and works on all your devices.
            Voice is convenience; Touch ID or your phone stays the lock.
          </p>
        </section>

        <section v-if="section === 'Updates'" class="card col" aria-labelledby="updates-heading">
          <div class="head">
            <h2 id="updates-heading">Jarvis app</h2>
            <p class="t-sm t-2">Private updates through your Home Node.</p>
          </div>
          <ul class="kv">
            <li><span class="k">Version</span><span class="v mono">v{{ currentAppVersion }}</span></li>
            <li>
              <span class="k">Status</span>
              <span class="v">
                <span class="dot" :class="updateState === 'error' ? 'dot-err' : updateState === 'available' || updateState === 'ready_to_restart' ? 'dot-todo' : 'dot-ok'"></span>
                <template v-if="updateState === 'checking'">checking…</template>
                <template v-else-if="updateState === 'downloading'">downloading{{ updateProgress === null ? '…' : ` · ${updateProgress}%` }}</template>
                <template v-else-if="updateState === 'installing'">installing…</template>
                <template v-else-if="updateState === 'available'">v{{ availableAppVersion }} available</template>
                <template v-else-if="updateState === 'ready_to_restart'">v{{ availableAppVersion }} ready · restarts when idle</template>
                <template v-else-if="updateState === 'up_to_date'">up to date · Home Node offers nothing newer than v{{ currentAppVersion }}</template>
                <template v-else-if="updateState === 'ready'">ready to check</template>
                <template v-else-if="updateState === 'unauthenticated'">sign in to check</template>
                <template v-else-if="updateState === 'unconfigured'">Home Node not configured</template>
                <template v-else-if="updateState === 'unsupported'">not available in this build</template>
                <template v-else-if="updateState === 'incompatible'">update protocol not compatible</template>
                <template v-else-if="updateState === 'unavailable'">update service unavailable</template>
                <template v-else-if="updateState === 'error'">update failed</template>
                <template v-else>ready</template>
              </span>
            </li>
            <li v-if="lastUpdateCheck !== null">
              <span class="k">Last check</span>
              <span class="v">{{ new Date(lastUpdateCheck * 1000).toLocaleString() }}</span>
            </li>
          </ul>
          <div v-if="updateState === 'downloading' || updateState === 'installing'" class="update-progress" aria-live="polite">
            <span :style="{ width: `${updateProgress ?? 15}%` }"></span>
          </div>
          <div class="actions">
            <button v-if="updateState === 'available'" type="button" class="btn btn-primary" :disabled="updateBusy" @click="installAvailableUpdate">
              Update now
            </button>
            <button v-else-if="updateState === 'ready_to_restart'" type="button" class="btn btn-primary" @click="restartAfterUpdate">
              Restart now
            </button>
            <button v-else type="button" class="btn btn-secondary" :disabled="updateBusy || updateState === 'unsupported'" @click="checkForUpdate">
              Check for updates
            </button>
          </div>
          <p v-if="updateError" class="t-sm errc">{{ updateError }}</p>
          <p v-else-if="updateNotes && (updateState === 'incompatible' || updateState === 'unavailable')" class="t-sm t-2">
            {{ updateNotes }}
          </p>
          <p v-else-if="updateState === 'ready_to_restart'" class="t-sm t-2">
            A reply or voice session is active. Jarvis installs the verified update and restarts as soon as it is idle,
            or now if you restart (this ends the session).
          </p>
          <p v-else class="t-sm t-2">
            Updates come through your paired Home Node and are verified cryptographically before installation.
          </p>
        </section>
      </div>
    </div>

    <!-- Destructive-action confirmation. -->
    <dialog ref="unlinkDialog" class="dialog" aria-labelledby="unlink-title" aria-describedby="unlink-body"
      @cancel="onUnlinkCancel" @close="confirmUnlink = false">
      <template v-if="confirmUnlink">
        <h2 id="unlink-title">Unlink this device?</h2>
        <p v-if="signer" id="unlink-body" class="t-sm t-2">
          This revokes the device on the server after Touch ID and erases its
          device key here. You then have to pair (enroll) this device again to
          sign in. Your data on the server stays.
        </p>
        <template v-else>
          <p id="unlink-body" class="t-sm t-2">
            This signs the device out and erases its device key here. You then
            have to pair (enroll) this device again to sign in. Your data on the
            server stays.
          </p>
          <p class="t-sm t-2">
            The device stays active on the Home Node (and trusted for model
            changes, if its key is pinned) until you revoke it there:
          </p>
          <pre class="mono">sudo jarvis devices revoke {{ session?.device_id ?? "<device id>" }}</pre>
        </template>
        <div class="dialog-actions">
          <button type="button" class="btn btn-secondary" autofocus :disabled="unlinking" @click="confirmUnlink = false">
            Cancel
          </button>
          <button type="button" class="btn btn-danger" :aria-busy="unlinking" @click="doUnlink">
            {{ unlinking ? "Unlinking…" : "Unlink" }}
          </button>
        </div>
      </template>
    </dialog>
  </PageShell>
</template>

<style scoped>
.settings-page { padding-bottom: 96px; }
.layout { display: flex; flex-wrap: wrap; gap: 28px; align-items: flex-start; overflow-wrap: anywhere; }
.sections { flex: 0 1 200px; display: flex; flex-direction: column; gap: 2px; }
.navlink {
  display: flex; align-items: center; min-height: 40px; padding: 0 12px; border-radius: var(--r-md);
  color: var(--text-primary); font-size: 15px; text-align: left; transition: background-color var(--d-hover) ease;
}
.navlink[aria-current="true"] { background: var(--bg-selected); color: var(--accent-text); font-weight: 600; }
.navlink:focus-visible { outline: 2px solid var(--focus-ring); outline-offset: 2px; }
@media (hover: hover) and (pointer: fine) {
  .navlink:not([aria-current="true"]):hover { background: var(--bg-hover); }
}
.content { flex: 999 1 560px; min-width: 0; max-width: 820px; display: flex; flex-direction: column; gap: 20px; }
.col { display: flex; flex-direction: column; gap: 14px; }
.head { display: flex; flex-direction: column; gap: 4px; }
.card p, .dialog p { margin: 0; }
.wrap { overflow-wrap: anywhere; user-select: text; }

.kv { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.kv li { display: flex; align-items: center; gap: 12px; }
.kv .k { font-size: 13px; color: var(--text-secondary); }
.kv .v { margin-left: auto; font-size: 13px; display: inline-flex; align-items: center; gap: 8px; text-align: right; }
.mono { font-size: 12px; }

.origin-row { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
.origin-row .input { flex: 1 1 20rem; }
select.input { padding-right: 8px; }
.actions { display: flex; gap: 10px; flex-wrap: wrap; }

.toggle { display: flex; align-items: center; gap: 14px; cursor: pointer; }
.toggle.off { cursor: default; }
.tl { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.tl .t-xs { max-width: 46ch; }
.toggle .switch { margin-left: auto; }

.update-progress { height: 6px; overflow: hidden; border-radius: var(--r-pill); background: var(--bg-sunken); }
.update-progress span {
  display: block; height: 100%; min-width: 8px; border-radius: inherit;
  background: var(--accent-solid); transition: width 0.2s ease;
}
.okc { color: var(--positive-text); }
.errc { color: var(--danger-text); }

.dialog h2 { margin: 0; }
/* styles.css .dot (9px) loads after components.css; badge dots stay 6px as on the canvas. */
.badge .dot { width: 6px; height: 6px; }
.dialog pre { margin: 0; white-space: pre-wrap; overflow-wrap: anywhere; user-select: text; font-size: 12px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
</style>
