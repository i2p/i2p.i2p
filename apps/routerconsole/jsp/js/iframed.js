function injectClass(f) {
    f.className += ' iframed';
    var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
    doc.body.className += ' iframed';
}
function resizeFrame(f) {
    // offsetHeight returns the height of the visible area for an object, in pixels.
    // The value contains the height with the padding, scrollBar, and the border,
    // but does not include the margin. Therefore, any content within the iframe
    // should have no margins at the very top or very bottom to avoid a scrollbar.
    var doc = 'contentDocument' in f? f.contentDocument : f.contentWindow.document;
    var totalHeight = doc.body.offsetHeight;

    // Detect if horizontal scrollbar is present, and add its width to height if so.
    // This prevents a vertical scrollbar appearing when the min-width is passed.
    // FIXME: How to detect horizontal scrollbar in iframe? Always apply for now.
    if (true) {
        // Create the measurement node
        var scrollDiv = document.createElement("div");
        scrollDiv.className = "scrollbar-measure";
        scrollDiv.style.width = "100px";
        scrollDiv.style.height = "100px";
        scrollDiv.style.overflow = "scroll";
        scrollDiv.style.position = "absolute";
        scrollDiv.style.top = "-9999px";
        document.body.appendChild(scrollDiv);

        // Get the scrollbar width
        var scrollbarWidth = scrollDiv.offsetWidth - scrollDiv.clientWidth;
        totalHeight += scrollbarWidth;

        // Delete the div
        document.body.removeChild(scrollDiv);
    }

    f.style.height = totalHeight + "px";
}

