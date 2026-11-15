"use strict";

document.addEventListener("DOMContentLoaded", () => {
    const form = document.getElementById("catalog-search");
    const query = document.getElementById("catalog-query");
    const results = document.getElementById("catalog-results");
    if (!form || !query || !results) return;
    let controller;
    let stopped = false;

    async function update() {
        if (stopped || document.hidden) return;
        if (controller) controller.abort();
        const request = new AbortController();
        controller = request;
        try {
            const url = new URL(results.dataset.url, window.location.origin);
            url.searchParams.set("nf_catalogq", query.value.slice(0, 80));
            const response = await fetch(url, {
                credentials: "same-origin", cache: "no-store", signal: request.signal
            });
            if (!response.ok) return;
            const content = await response.text();
            if (!stopped && controller === request) results.innerHTML = content;
        } catch (error) {
            // The normal GET search form remains available if polling fails.
        }
    }

    form.addEventListener("submit", (event) => {
        event.preventDefault();
        update();
    });
    query.addEventListener("input", () => {
        document.querySelectorAll('#peer-catalog input[type="hidden"][name="nf_catalogq"]').forEach((input) => {
            input.value = query.value.slice(0, 80);
        });
    });
    const timer = window.setInterval(update, 5000);
    window.addEventListener("pagehide", () => {
        stopped = true;
        window.clearInterval(timer);
        if (controller) controller.abort();
    }, {once: true});
    // This endpoint reads cached listings only. Peer requests require the POST button.
    update();
});
