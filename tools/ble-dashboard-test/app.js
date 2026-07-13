const SERVICE_UUID = "ca7b0001-6f6f-4d9f-9d78-3d9c4a9ed001";
const PAYLOAD_UUID = "ca7b0002-6f6f-4d9f-9d78-3d9c4a9ed001";
const MAX_PAYLOAD_BYTES = 320;

const connectButton = document.querySelector("#connect");
const disconnectButton = document.querySelector("#disconnect");
const sendButton = document.querySelector("#send");
const form = document.querySelector("#dashboard-form");
const connectionStatus = document.querySelector("#connection-status");
const sendStatus = document.querySelector("#send-status");
const payloadSize = document.querySelector("#payload-size");
const encoder = new TextEncoder();

let device;
let payloadCharacteristic;

function dashboardPayload() {
  return [
    document.querySelector("#title").value,
    document.querySelector("#message").value,
    document.querySelector("#footer").value,
  ].join("\n");
}

function updatePayloadSize() {
  const size = encoder.encode(dashboardPayload()).byteLength;
  payloadSize.textContent = `${size} / ${MAX_PAYLOAD_BYTES} UTF-8 bytes`;
  sendButton.disabled = !payloadCharacteristic || size === 0 || size > MAX_PAYLOAD_BYTES;
}

function setConnectionStatus(message) {
  connectionStatus.textContent = message;
}

function disconnected() {
  payloadCharacteristic = undefined;
  disconnectButton.disabled = true;
  setConnectionStatus("Disconnected");
  updatePayloadSize();
}

async function connect() {
  if (!navigator.bluetooth) {
    setConnectionStatus("Web Bluetooth is not available in this browser.");
    return;
  }

  try {
    setConnectionStatus("Choose CrossPoint Dashboard in the browser picker…");
    device = await navigator.bluetooth.requestDevice({ filters: [{ services: [SERVICE_UUID] }] });
    device.addEventListener("gattserverdisconnected", disconnected, { once: true });
    setConnectionStatus("Connecting…");
    const server = await device.gatt.connect();
    const service = await server.getPrimaryService(SERVICE_UUID);
    payloadCharacteristic = await service.getCharacteristic(PAYLOAD_UUID);
    disconnectButton.disabled = false;
    setConnectionStatus(`Connected to ${device.name || "CrossPoint Dashboard"}`);
    updatePayloadSize();
  } catch (error) {
    setConnectionStatus(`Connection failed: ${error.message}`);
    updatePayloadSize();
  }
}

async function sendPayload(event) {
  event.preventDefault();
  const bytes = encoder.encode(dashboardPayload());
  if (!payloadCharacteristic || bytes.byteLength === 0 || bytes.byteLength > MAX_PAYLOAD_BYTES) {
    return;
  }

  try {
    sendStatus.textContent = "Sending…";
    if (payloadCharacteristic.writeValueWithResponse) {
      await payloadCharacteristic.writeValueWithResponse(bytes);
    } else {
      await payloadCharacteristic.writeValue(bytes);
    }
    sendStatus.textContent = "Dashboard update sent.";
  } catch (error) {
    sendStatus.textContent = `Send failed: ${error.message}`;
  }
}

connectButton.addEventListener("click", connect);
disconnectButton.addEventListener("click", () => device?.gatt?.disconnect());
form.addEventListener("submit", sendPayload);
form.querySelectorAll("input, textarea").forEach((field) => field.addEventListener("input", updatePayloadSize));
updatePayloadSize();
