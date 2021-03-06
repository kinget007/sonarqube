/*
 * SonarQube, open source software quality management tool.
 * Copyright (C) 2008-2014 SonarSource
 * mailto:contact AT sonarsource DOT com
 *
 * SonarQube is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * SonarQube is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
(function($) {

  function stripe(rows) {
    rows.each(function(index) {
      $(this).toggleClass('rowodd', index % 2 === 0);
      $(this).toggleClass('roweven', index % 2 !== 0);
    });
  }


  function getValue(cell) {
    return cell.attr('x') || $.trim(cell.text()) || '';
  }


  function sort(container, rows, cellIndex, order) {
    var sortArray = rows.map(function(index) {
      var cell = $(this).find('td').eq(cellIndex);
      return { index: index, value: getValue(cell) };
    }).get();

    Array.prototype.sort.call(sortArray, function(a, b) {
      if (isNaN(a.value) || isNaN(b.value)) {
        return order * (a.value > b.value ? 1 : -1);
      } else {
        return order * (a.value - b.value);
      }
    });

    rows.detach();
    sortArray.forEach(function(a) {
      var row = rows[a.index];
      container.append(row);
    });

    stripe(container.find('tr'));
  }


  function markSorted(headCells, cell, asc) {
    headCells.removeClass('sortasc sortdesc');
    cell.toggleClass('sortasc', asc);
    cell.toggleClass('sortdesc', !asc);
  }


  $.fn.sortable = function() {
    return $(this).each(function() {
      var thead = $(this).find('thead'),
          tbody = $(this).find('tbody'),
          headCells = thead.find('tr:last th'),
          rows = tbody.find('tr');

       headCells.filter(':not(.nosort)').addClass('sortcol');
       headCells.filter(':not(.nosort)').on('click', function() {
         var toAsc = !$(this).is('.sortasc');
         markSorted(headCells, $(this), toAsc);
         sort(tbody, rows, headCells.index($(this)), toAsc ? 1 : -1);
       });

      var sortFirst = headCells.filter('[class^=sortfirst],[class*=sortfirst]');
      if (sortFirst.length > 0) {
        var asc = sortFirst.is('.sortfirstasc');
        markSorted(headCells, sortFirst, asc);
        sort(tbody, rows, headCells.index(sortFirst), asc ? 1 : -1);
      } else {
        stripe(rows);
      }
    });
  };

})(jQuery);
