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

package org.sonar.server.computation.period;

import java.util.Calendar;
import java.util.Date;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.builder.ReflectionToStringBuilder;
import org.sonar.api.CoreProperties;
import org.sonar.api.utils.DateUtils;
import org.sonar.core.component.SnapshotDto;

import static org.sonar.api.utils.DateUtils.longToDate;

public class Period {

  private int index;
  private String mode, modeParameter;

  private SnapshotDto snapshot;

  /**
   * Only the root snapshot id (root id or id (if no root id) of the snapshot) and snapshot creation date are needed
   */
  private SnapshotDto projectSnapshot;

  /**
   * Date deduced from the settings
   */
  private Long targetDate = null;

  public Period(String mode, @Nullable Long targetDate, @Nullable SnapshotDto projectSnapshot) {
    this.mode = mode;
    if (targetDate != null) {
      this.targetDate = targetDate;
    }
    this.projectSnapshot = projectSnapshot;
  }

  /**
    * SONAR-2428 : even if previous analysis does not exist (no snapshot and no target date), we should perform comparison.
    */
  public Period(String mode) {
    this(mode, null, null);
  }

  public Period setIndex(int index) {
    this.index = index;
    return this;
  }

  public int getIndex() {
    return index;
  }

  public SnapshotDto getProjectSnapshot() {
    return projectSnapshot;
  }

  /**
   * Date of the snapshot
   */
  public Date getDate() {
    return projectSnapshot != null ? longToDate(projectSnapshot.getCreatedAt()) : null;
  }

  public Period setMode(String mode) {
    this.mode = mode;
    return this;
  }

  public String getMode() {
    return mode;
  }

  public String getModeParameter() {
    return modeParameter;
  }

  public Period setModeParameter(String s) {
    this.modeParameter = s;
    return this;
  }

  @Override
  public String toString() {
    if (StringUtils.equals(mode, CoreProperties.TIMEMACHINE_MODE_VERSION)) {
      String label = String.format("Compare to version %s", modeParameter);
      if (targetDate != null) {
        label += String.format(" (%s)", DateUtils.formatDate(getDate()));
      }
      return label;
    }
    if (StringUtils.equals(mode, CoreProperties.TIMEMACHINE_MODE_DAYS)) {
      String label = String.format("Compare over %s days (%s", modeParameter, formatDate());
      if (isRelatedToSnapshot()) {
        label += ", analysis of " + getDate();
      }
      label += ")";
      return label;
    }
    if (StringUtils.equals(mode, CoreProperties.TIMEMACHINE_MODE_PREVIOUS_ANALYSIS)) {
      String label = "Compare to previous analysis";
      if (isRelatedToSnapshot()) {
        label += String.format(" (%s)", DateUtils.formatDate(getDate()));
      }
      return label;
    }
    if (StringUtils.equals(mode, CoreProperties.TIMEMACHINE_MODE_PREVIOUS_VERSION)) {
      String label = "Compare to previous version";
      if (isRelatedToSnapshot()) {
        label += String.format(" (%s)", DateUtils.formatDate(getDate()));
      }
      return label;
    }
    if (StringUtils.equals(mode, CoreProperties.TIMEMACHINE_MODE_DATE)) {
      String label = "Compare to date " + formatDate();
      if (isRelatedToSnapshot()) {
        label += String.format(" (analysis of %s)", DateUtils.formatDate(getDate()));
      }
      return label;
    }
    return ReflectionToStringBuilder.toString(this);
  }

  private boolean isRelatedToSnapshot() {
    return projectSnapshot != null;
  }

  private String formatDate() {
    return DateUtils.formatDate(org.apache.commons.lang.time.DateUtils.truncate(new Date(targetDate), Calendar.SECOND));
  }

}
