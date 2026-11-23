/* Parent frame resizing tests; no child scripts or browser/network dependency. */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const root = path.resolve(__dirname, "../..");
const source = process.argv[2] || root;
let checks = 0;
let failures = 0;

function check(name, operation) {
    checks++;
    try { operation(); }
    catch (error) { failures++; console.log("FAIL: " + name + ": " + error.message); }
}

function target(values = {}) {
    const listeners = new Map();
    return Object.assign(values, {
        addEventListener(name, listener) {
            if (!listeners.has(name)) listeners.set(name, []);
            listeners.get(name).push(listener);
        },
        emit(name, event) { for (const listener of listeners.get(name) || []) listener(event); }
    });
}

const first = target({ contentDocument: null, style: {} });
const second = target({ contentDocument: target({ body: { offsetHeight: 240 } }), style: {} });
const otherOrigin = target({ style: {} });
Object.defineProperty(otherOrigin, "contentDocument", { get() { throw new Error("cross-origin navigation"); } });
const document = target({
    getElementsByClassName() { return [first, second, otherOrigin]; },
    createElement() { return { style: {}, offsetWidth: 100, clientWidth: 85 }; },
    body: { appendChild() {}, removeChild() {} }
});
let destination = null;
const window = target({ location: { assign(value) { destination = value; } } });
const context = vm.createContext({ document, window });
vm.runInContext(fs.readFileSync(path.join(source, "apps/susimail/src/js/iframer.js"), "utf8"), context);
vm.runInContext(fs.readFileSync(path.join(root, "apps/routerconsole/jsp/js/iframed.js"), "utf8"), context);
check("setup tolerates unloaded and navigated frames", () => document.emit("DOMContentLoaded"));
check("already loaded mail is resized", () => assert.equal(second.style.height, "275px"));
first.contentDocument = target({ body: { offsetHeight: 120 } });
check("mail resizes after load without a script in the child", () => {
    first.emit("load");
    assert.equal(first.style.height, "155px");
    assert.equal(second.style.height, "275px");
});
check("late inline images resize the correct mail frame", () => {
    first.contentDocument.body.offsetHeight = 360;
    first.contentDocument.emit("load");
    assert.equal(first.style.height, "395px");
    assert.equal(second.style.height, "275px");
});
check("window resize updates each frame independently", () => {
    second.contentDocument.body.offsetHeight = 400;
    window.emit("resize");
    assert.equal(second.style.height, "435px");
    assert.equal(first.style.height, "395px");
});
check("navigation away from a message does not break the parent", () => {
    first.contentDocument = null;
    first.emit("load");
    otherOrigin.emit("load");
    window.emit("resize");
});
console.log("SUSIMAIL_FRAME_CHECKS=" + checks + "; FAILURES=" + failures);
process.exitCode = failures ? 1 : 0;
