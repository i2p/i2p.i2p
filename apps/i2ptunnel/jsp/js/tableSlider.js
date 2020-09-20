/* @license http://www.gnu.org/licenses/gpl-2.0.html GPL-2.0 */

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
    function hideRow(row) {
        if (Object.keys(row.querySelectorAll(".buttons")).length < 1 ) {
            if (Object.keys(row.querySelectorAll("th")).length < 1 ) {
                row.style.visibility = "collapse";
            }
        } else if (Object.keys(row.querySelectorAll("th")).length < 1 ) {
            if (Object.keys(row.querySelectorAll(".buttons")).length < 1 ) {
                row.style.visibility = "collapse";
            }
        } else {
            configTable.style.visibility = "visible"
        }
    }
    function showAllControls() {
        for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
            hideRow(row)
        }
    }
    configTable.onclick = function() {
        var minRow = lookupTableRow();
        for (var i = 0, row; (row = configTable.offsetParent.rows[i]); i++) {
            if (i >= minRow) {
                if (row.classList.contains("tunnelConfigExpanded")) {
                    hideRow(row)
                    row.querySelectorAll("th").forEach(function(configRow) {
                        configRow.classList.remove('tunnelConfigExpanded');
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
        var iiframe = document.getElementById("i2ptunnelframe");
        if (iiframe != null) {
            var adjustHeight = 0;
            for (var child = frame.firstChild; child !== null; child.nextSibling){
                adjustHeight += child.offsetHeight;
            }
            iiframe.height = adjustHeight;
        }
    };
    showAllControls()
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

/* @license-end */
