var hideableTables = document.querySelectorAll("table.tunnelConfig th");

hideableTables.forEach(function(configTable) {
    configTable.onclick = function() {
        function lookupTableRow() {
            for (
                var i = 0, row;
                (row = configTable.offsetParent.rows[i]);
                i++
            ) {
                if (configTable.parentNode == row) {
                    return i;
                }
            }
            return -1;
        }
        var collapseme = false;
        for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
            var l = lookupTableRow();
            if (i > l) {
                if (collapseme) {
                    if (row.firstElementChild.localName != "th") {
                        if (
                            !row.firstElementChild.classList.contains("buttons")
                        ) {
                            row.style.visibility = "collapse";
                        }
                    }
                } else if (row.style.visibility == "visible") {
                    if (row.firstElementChild.localName != "th") {
                        if (
                            !row.firstElementChild.classList.contains("buttons")
                        ) {
                            row.style.visibility = "collapse";
                            collapseme = true;
                            configTable.classList.remove(
                                "tunnelConfigExpanded"
                            );
                        }
                    }
                } else {
                    row.style.visibility = "visible";
                    configTable.classList.add("tunnelConfigExpanded");
                }
            }
        }
        configTable.parentNode.style.visibility = "visible";
    };
    for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
        if (row.firstElementChild.localName != "th") {
            if (!row.firstElementChild.classList.contains("buttons")) {
                row.style.visibility = "collapse";
            }
        }
    }
    for (var i = 0, row; (row = hideableTables[0].offsetParent.rows[i]); i++) {
        row.style.visibility = "visible";
    }
});
