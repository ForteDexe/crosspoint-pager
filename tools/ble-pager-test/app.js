const SERVICE_UUID = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
const PAYLOAD_UUID = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
const STATUS_UUID = "ca7b0003-6f6f-4d9f-9d78-3d9c4a9ed001";
const MAX_PAYLOAD_BYTES = 320;

const connectButton = document.querySelector("#connect");
const disconnectButton = document.querySelector("#disconnect");
const refreshPolicyButton = document.querySelector("#refresh-policy");
const sendButton = document.querySelector("#send");
const form = document.querySelector("#pager-form");
const connectionStatus = document.querySelector("#connection-status");
const policyStatus = document.querySelector("#policy-status");
const sendStatus = document.querySelector("#send-status");
const payloadSize = document.querySelector("#payload-size");
const encoder = new TextEncoder();
const decoder = new TextDecoder();

let device;
let payloadCharacteristic;
let statusCharacteristic;
let lastAcknowledgedPayload;

function pagerPayload() {
  return [
    document.querySelector("#title").value,
    document.querySelector("#message").value,
    document.querySelector("#footer").value,
  ].join("\n");
}

function updatePayloadSize() {
  const size = encoder.encode(pagerPayload()).byteLength;
  payloadSize.textContent = `${size} / ${MAX_PAYLOAD_BYTES} UTF-8 bytes`;
  sendButton.disabled = !payloadCharacteristic || size === 0 || size > MAX_PAYLOAD_BYTES;
}

function setConnectionStatus(message) {
  connectionStatus.textContent = message;
}

function disconnected() {
  payloadCharacteristic = undefined;
  statusCharacteristic = undefined;
  disconnectButton.disabled = true;
  refreshPolicyButton.disabled = true;
  setConnectionStatus("Disconnected");
  policyStatus.textContent = "Connect to read the device-owned policy.";
  updatePayloadSize();
}

function parseStatus(text) {
  return Object.fromEntries(
    text.split(";").filter(Boolean).map((entry) => {
      const separator = entry.indexOf("=");
      return separator < 0 ? [entry, ""] : [entry.slice(0, separator), entry.slice(separator + 1)];
    }),
  );
}

function titleCaseProfile(profile) {
  return (profile || "unknown").split("_").map((word) => word[0]?.toUpperCase() + word.slice(1)).join(" ");
}

function formatNumber(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(2).replace(/0+$/, "").replace(/\.$/, "");
}

function renderPolicy(rawStatus) {
  const status = parseStatus(rawStatus);
  const intervalSeconds = Number(status.interval_s || 0);
  const windowSeconds = Number(status.window_ms || 0) / 1000;
  const availability = status.availability === "always"
    ? "Always Available"
    : `Every ${intervalSeconds / 60} min (${windowSeconds} s receive window)`;

  const details = [
    `Availability: ${availability}`,
    `Always available profile: ${titleCaseProfile(status.profile)}`,
    `BLE link: ${status.connected === "1" ? "connected" : "not connected"}`,
  ];

  if (status.connected === "1" && Number(status.conn_interval_units) > 0) {
    const intervalMs = Number(status.conn_interval_units) * 1.25;
    const supervisionTimeoutMs = Number(status.conn_timeout_units) * 10;
    details.push(`Negotiated link timing: ${formatNumber(intervalMs)} ms interval, latency ${status.conn_latency}, ${formatNumber(supervisionTimeoutMs)} ms timeout`);
  }

  policyStatus.textContent = details.join("\n");
}

async function refreshPagerStatus() {
  if (!statusCharacteristic) {
    return;
  }

  try {
    policyStatus.textContent = "Reading X3 policy…";
    const value = await statusCharacteristic.readValue();
    renderPolicy(decoder.decode(value));
  } catch (error) {
    policyStatus.textContent = `Policy read failed: ${error.message}`;
  }
}

async function connect() {
  if (!navigator.bluetooth) {
    setConnectionStatus("Web Bluetooth is not available in this browser.");
    return;
  }

  try {
    setConnectionStatus("Choose CrossPoint Pager in the browser picker…");
    device = await navigator.bluetooth.requestDevice({ filters: [{ services: [SERVICE_UUID] }] });
    device.addEventListener("gattserverdisconnected", disconnected, { once: true });
    setConnectionStatus("Connecting…");
    const server = await device.gatt.connect();
    const service = await server.getPrimaryService(SERVICE_UUID);
    payloadCharacteristic = await service.getCharacteristic(PAYLOAD_UUID);
    try {
      statusCharacteristic = await service.getCharacteristic(STATUS_UUID);
      refreshPolicyButton.disabled = false;
      await refreshPagerStatus();
    } catch (error) {
      statusCharacteristic = undefined;
      refreshPolicyButton.disabled = true;
      policyStatus.textContent = `Policy status is unavailable on this firmware: ${error.message}`;
    }
    disconnectButton.disabled = false;
    setConnectionStatus(`Connected to ${device.name || "CrossPoint Pager"}`);
    updatePayloadSize();
  } catch (error) {
    setConnectionStatus(`Connection failed: ${error.message}`);
    updatePayloadSize();
  }
}

async function sendPayload(event) {
  event.preventDefault();
  const payload = pagerPayload();
  const bytes = encoder.encode(payload);
  if (!payloadCharacteristic || bytes.byteLength === 0 || bytes.byteLength > MAX_PAYLOAD_BYTES) {
    return;
  }

  try {
    sendStatus.textContent = "Sending…";
    const isIdentical = payload === lastAcknowledgedPayload;
    if (payloadCharacteristic.writeValueWithResponse) {
      await payloadCharacteristic.writeValueWithResponse(bytes);
    } else {
      await payloadCharacteristic.writeValue(bytes);
    }
    lastAcknowledgedPayload = payload;
    sendStatus.textContent = isIdentical
      ? "Pager update acknowledged.\nMESSAGE IDENTICAL, X3 WILL NOT UPDATE CONTENT!"
      : "Pager update acknowledged.";
  } catch (error) {
    sendStatus.textContent = `Send failed: ${error.message}`;
  }
}

connectButton.addEventListener("click", connect);
disconnectButton.addEventListener("click", () => device?.gatt?.disconnect());
refreshPolicyButton.addEventListener("click", refreshPagerStatus);
form.addEventListener("submit", sendPayload);
form.querySelectorAll("input, textarea").forEach((field) => field.addEventListener("input", updatePayloadSize));
updatePayloadSize();
