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

import com.google.common.base.Strings;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.CoreProperties;
import org.sonar.api.config.Settings;
import org.sonar.api.resources.Qualifiers;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.server.computation.batch.BatchReportReader;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.TreeRootHolder;
import org.sonar.server.db.DbClient;

public class PeriodsRepository {

  private static final Logger LOG = LoggerFactory.getLogger(PeriodsRepository.class);

  private static final int NUMBER_OF_PERIODS = 5;

  private final DbClient dbClient;
  private final Settings settings;
  private final TreeRootHolder treeRootHolder;
  private final PeriodFinder periodFinder;
  private final BatchReportReader batchReportReader;

  private List<Period> periods = new ArrayList<>();

  public PeriodsRepository(DbClient dbClient, Settings settings, TreeRootHolder treeRootHolder, PeriodFinder periodFinder, BatchReportReader batchReportReader) {
    this.dbClient = dbClient;
    this.settings = settings;
    this.treeRootHolder = treeRootHolder;
    this.periodFinder = periodFinder;
    this.batchReportReader = batchReportReader;
  }

  public void initPeriods() {
    DbSession session = dbClient.openSession(false);
    try {
      Component project = treeRootHolder.getRoot();
      ComponentDto projectDto = dbClient.componentDao().selectNullableByKey(session, project.getKey());
      // No project on first analysis, no period
      if (projectDto != null) {
        BatchReport.Component batchProject = batchReportReader.readComponent(project.getRef());
        PeriodResolver periodResolver = new PeriodResolver(session, projectDto.getId(), batchReportReader.readMetadata().getAnalysisDate(), batchProject.getVersion(),
          // TODO qualifier will be different for Views
          Qualifiers.PROJECT);

        for (int index = 1; index <= NUMBER_OF_PERIODS; index++) {
          // get Period
          Period period = periodResolver.findPeriod(index);

          // TODO load snapshot of the period

          // SONAR-4700 Add a past snapshot only if it exists
          if (period != null && period.getProjectSnapshot() != null) {
            periods.add(period);
          }
        }
      }
    } finally {
      session.close();
    }
  }

  private class PeriodResolver {

    private final DbSession session;
    private final long projectId;
    private final long analysisDate;
    private final String currentVersion;
    private final String qualifier;

    public PeriodResolver(DbSession session, long projectId, long analysisDate, String currentVersion, String qualifier) {
      this.session = session;
      this.projectId = projectId;
      this.analysisDate = analysisDate;
      this.currentVersion = currentVersion;
      this.qualifier = qualifier;
    }

    private Period findPeriod(int index) {
      String propertyValue = getPropertyValue(qualifier, settings, index);
      Period period = find(index, propertyValue);
      if (period == null && StringUtils.isNotBlank(propertyValue)) {
        LOG.debug("Property " + CoreProperties.TIMEMACHINE_PERIOD_PREFIX + index + " is not valid: " + propertyValue);
      }
      return period;
    }

    @Nullable
    private Period find(int index, String property) {
      if (StringUtils.isBlank(property)) {
        return null;
      }

      Period result = findByDays(property);
      if (result == null) {
        result = findByDate(property);
        if (result == null) {
          result = findByPreviousAnalysis(property);
          if (result == null) {
            result = findByPreviousVersion(property);
            if (result == null) {
              result = findByVersion(property);
            }
          }
        }
      }

      if (result != null) {
        result.setIndex(index);
      }

      return result;
    }

    @CheckForNull
    private Period findByDays(String property) {
      try {
        int days = Integer.parseInt(property);
        return periodFinder.findByDays(session, projectId, analysisDate, days);
      } catch (NumberFormatException e) {
        return null;
      }
    }

    @CheckForNull
    private Period findByDate(String property) {
      SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
      try {
        Date date = format.parse(property);
        return periodFinder.findByDate(session, projectId, date.getTime());

      } catch (ParseException e) {
        return null;
      }
    }

    @CheckForNull
    private Period findByPreviousAnalysis(String property) {
      if (StringUtils.equals(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_ANALYSIS, property)) {
        return periodFinder.findByPreviousAnalysis(session, projectId, analysisDate);
      }
      return null;
    }

    @CheckForNull
    private Period findByPreviousVersion(String property) {
      if (StringUtils.equals(CoreProperties.TIMEMACHINE_MODE_PREVIOUS_VERSION, property)) {
        return periodFinder.findByPreviousVersion(session, projectId, currentVersion);
      }
      return null;
    }

    private Period findByVersion(String version) {
      return periodFinder.findByVersion(session, projectId, version);
    }
  }

  private static String getPropertyValue(@Nullable String qualifier, Settings settings, int index) {
    String value = settings.getString(CoreProperties.TIMEMACHINE_PERIOD_PREFIX + index);
    // For periods 4 and 5 we're also searching for a property prefixed by the qualifier
    if (index > 3 && Strings.isNullOrEmpty(value)) {
      value = settings.getString(CoreProperties.TIMEMACHINE_PERIOD_PREFIX + index + "." + qualifier);
    }
    return value;
  }

}
