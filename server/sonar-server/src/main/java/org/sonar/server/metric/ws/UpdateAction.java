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

package org.sonar.server.metric.ws;

import java.net.HttpURLConnection;
import java.util.List;
import javax.annotation.Nullable;
import org.sonar.api.measures.Metric;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.core.custommeasure.db.CustomMeasureDto;
import org.sonar.core.metric.db.MetricDto;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.MyBatis;
import org.sonar.server.db.DbClient;
import org.sonar.server.exceptions.ServerException;
import org.sonar.server.user.UserSession;

public class UpdateAction implements MetricsWsAction {
  private static final String ACTION = "update";

  public static final String PARAM_ID = "id";
  public static final String PARAM_NAME = "name";
  public static final String PARAM_KEY = "key";
  public static final String PARAM_TYPE = "type";
  public static final String PARAM_DESCRIPTION = "description";
  public static final String PARAM_DOMAIN = "domain";

  private static final String FIELD_ID = PARAM_ID;
  private static final String FIELD_NAME = PARAM_NAME;
  private static final String FIELD_KEY = PARAM_KEY;
  private static final String FIELD_TYPE = PARAM_TYPE;
  private static final String FIELD_DESCRIPTION = PARAM_DESCRIPTION;
  private static final String FIELD_DOMAIN = PARAM_DOMAIN;

  private final DbClient dbClient;
  private final UserSession userSession;

  public UpdateAction(DbClient dbClient, UserSession userSession) {
    this.dbClient = dbClient;
    this.userSession = userSession;
  }

  @Override
  public void define(WebService.NewController context) {
    WebService.NewAction action = context.createAction(ACTION)
      .setPost(true)
      .setDescription("Update a custom metric.<br /> Requires 'Administer System' permission.")
      .setSince("5.2")
      .setHandler(this);

    action.createParam(PARAM_ID)
      .setRequired(true)
      .setDescription("Id of the custom metric to update")
      .setExampleValue("42");

    action.createParam(PARAM_NAME)
      .setDescription("Name")
      .setExampleValue("Team Size");

    action.createParam(PARAM_KEY)
      .setDescription("Key")
      .setExampleValue("team_size");

    action.createParam(PARAM_TYPE)
      .setDescription("Type")
      .setPossibleValues(Metric.ValueType.names());

    action.createParam(PARAM_DESCRIPTION)
      .setDescription("Description")
      .setExampleValue("Size of the team");

    action.createParam(PARAM_DOMAIN)
      .setDescription("Domain")
      .setExampleValue("Tests");
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    userSession.checkLoggedIn().checkGlobalPermission(GlobalPermissions.SYSTEM_ADMIN);
    int id = request.mandatoryParamAsInt(PARAM_ID);

    DbSession dbSession = dbClient.openSession(false);
    try {
      MetricDto metricTemplate = newMetricTemplate(request);
      MetricDto metricInDb = dbClient.metricDao().selectNullableById(dbSession, id);
      checkMetricInDbAndTemplate(dbSession, metricInDb, metricTemplate);

      updateMetricInDb(dbSession, metricInDb, metricTemplate);
      JsonWriter json = response.newJsonWriter();
      writeMetric(json, metricInDb);
      json.close();
    } finally {
      MyBatis.closeQuietly(dbSession);
    }
  }

  private MetricDto newMetricTemplate(Request request) {
    int id = request.mandatoryParamAsInt(PARAM_ID);
    String key = request.param(PARAM_KEY);
    String type = request.param(PARAM_TYPE);
    String name = request.param(PARAM_NAME);
    String domain = request.param(PARAM_DOMAIN);
    String description = request.param(PARAM_DESCRIPTION);

    MetricDto metricTemplate = new MetricDto().setId(id);
    if (key != null) {
      metricTemplate.setKey(key);
    }
    if (type != null) {
      metricTemplate.setValueType(type);
    }
    if (name != null) {
      metricTemplate.setShortName(name);
    }
    if (domain != null) {
      metricTemplate.setDomain(domain);
    }
    if (description != null) {
      metricTemplate.setDescription(description);
    }
    return metricTemplate;
  }

  private void updateMetricInDb(DbSession dbSession, MetricDto metricInDb, MetricDto metricTemplate) {
    String key = metricTemplate.getKey();
    String name = metricTemplate.getShortName();
    String type = metricTemplate.getValueType();
    String domain = metricTemplate.getDomain();
    String description = metricTemplate.getDescription();
    if (key != null) {
      metricInDb.setKey(key);
    }
    if (name != null) {
      metricInDb.setShortName(name);
    }
    if (type != null) {
      metricInDb.setValueType(type);
    }
    if (domain != null) {
      metricInDb.setDomain(domain);
    }
    if (description != null) {
      metricInDb.setDescription(description);
    }
    dbClient.metricDao().update(dbSession, metricInDb);
    dbSession.commit();
  }

  private void checkMetricInDbAndTemplate(DbSession dbSession, @Nullable MetricDto metricInDb, MetricDto template) {
    if (isMetricFoundInDb(metricInDb) || isMetricDisabled(metricInDb) || isMetricNonCustom(metricInDb)) {
      throw new ServerException(HttpURLConnection.HTTP_CONFLICT, String.format("No active custom metric has been found for id '%d'. Maybe you want to create a metric ?",
        template.getId()));
    }
    if (haveMetricTypeChanged(metricInDb, template)) {
      List<CustomMeasureDto> customMeasures = dbClient.customMeasureDao().selectByMetricId(dbSession, metricInDb.getId());
      if (haveAssociatedCustomMeasures(customMeasures)) {
        throw new ServerException(HttpURLConnection.HTTP_CONFLICT, String.format("You're trying to change the type '%s' while there are associated custom measures.",
          metricInDb.getValueType()));
      }
    }
  }

  private void writeMetric(JsonWriter json, MetricDto metric) {
    json.beginObject();
    json.prop(FIELD_ID, String.valueOf(metric.getId()));
    json.prop(FIELD_KEY, metric.getKey());
    json.prop(FIELD_TYPE, metric.getValueType());
    json.prop(FIELD_NAME, metric.getShortName());
    json.prop(FIELD_DOMAIN, metric.getDomain());
    json.prop(FIELD_DESCRIPTION, metric.getDescription());
    json.endObject();
  }

  private boolean isMetricNonCustom(@Nullable MetricDto metricInDb) {
    return !metricInDb.isUserManaged();
  }

  private boolean isMetricDisabled(@Nullable MetricDto metricInDb) {
    return !metricInDb.isEnabled();
  }

  private boolean isMetricFoundInDb(@Nullable MetricDto metricInDb) {
    return metricInDb == null;
  }

  private boolean haveAssociatedCustomMeasures(List<CustomMeasureDto> customMeasures) {
    return !customMeasures.isEmpty();
  }

  private boolean haveMetricTypeChanged(@Nullable MetricDto metricInDb, MetricDto template) {
    return !metricInDb.getValueType().equals(template.getValueType()) && template.getValueType() != null;
  }
}
