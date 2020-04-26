function initTables() {

var hideableTables = document.querySelectorAll("table.tunnelConfig th");

hideableTables.forEach(function(configTable) {
    function lookupTableRow() {
        for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
            if (configTable.parentNode == row) {
                return i;
            }
        }
        return -1;
    }
    configTable.onclick = function() {
        var collapseme = false;
        for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
            var l = lookupTableRow();
            if (i >= l) {
                if (row.classList.contains("tunnelConfigExpanded")) {
                    row.style.visibility = "collapse";
                    row.querySelectorAll("th").forEach(function(configRow) {
                        configRow.classList.remove('tunnelConfigExpanded');
                        configRow.parentElement.style.visiblity = "visible"
                    });
                    row.querySelectorAll(".buttons").forEach(function(configRow) {
                        configRow.classList.remove('tunnelConfigExpanded');
                        configRow.parentElement.style.visiblity = "visible"
                    });
                    configTable.classList.remove('tunnelConfigExpanded');
                    row.classList.remove('tunnelConfigExpanded');
                } else {
                    row.style.visibility = "visible";
                    row.querySelectorAll("th").forEach(function(configRow) {
                        configRow.classList.add('tunnelConfigExpanded');
                    });
                    configTable.classList.add('tunnelConfigExpanded');
                    row.classList.add('tunnelConfigExpanded')
                    row.classList.add('excludeBackgroundImage')
                }
            }
        }
        configTable.parentNode.style.visibility = "visible";
    };
    for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
        if (row.firstElementChild.localName != "th") {
            if (!row.firstElementChild.classList.contains("buttons")) {
                row.style.visibility = "collapse";
                row.querySelectorAll("th").forEach(function(configRow) {
                    configRow.classList.remove('tunnelConfigExpanded');
                    configRow.parentElement.style.visiblity = "visible"
                });
                row.querySelectorAll(".buttons").forEach(function(configRow) {
                    configRow.classList.remove('tunnelConfigExpanded');
                    configRow.parentElement.style.visiblity = "visible"
                });
                configTable.classList.remove('tunnelConfigExpanded');
                row.classList.remove('tunnelConfigExpanded');
            }
        }
    }
    for (var i = 0, row; (row = hideableTables[0].offsetParent.rows[i]); i++) {
        row.style.visibility = "visible";
        row.querySelectorAll("th").forEach(function(configRow) {
            configRow.classList.add('tunnelConfigExpanded');
        });
    }
});

}

document.addEventListener("DOMContentLoaded", function() {
    initTables();
}, true);
