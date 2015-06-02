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
package org.sonar.server.user.ws;

import com.google.common.collect.ImmutableSet;
import java.util.Collection;
import java.util.Set;
import javax.annotation.Nullable;
import org.sonar.api.user.User;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.core.permission.GlobalPermissions;
import org.sonar.server.user.UserSession;
import org.sonar.server.user.index.UserDoc;

public class UserWriter {

  private static final String FIELD_LOGIN = "login";
  private static final String FIELD_NAME = "name";
  private static final String FIELD_EMAIL = "email";
  private static final String FIELD_SCM_ACCOUNTS = "scmAccounts";
  private static final String FIELD_GROUPS = "groups";
  private static final String FIELD_ACTIVE = "active";

  public static final Set<String> FIELDS = ImmutableSet.of(FIELD_NAME, FIELD_EMAIL, FIELD_SCM_ACCOUNTS, FIELD_GROUPS, FIELD_ACTIVE);
  private static final Set<String> CONCISE_FIELDS = ImmutableSet.of(FIELD_NAME, FIELD_EMAIL, FIELD_ACTIVE);

  private final UserSession userSession;

  public UserWriter(UserSession userSession) {
    this.userSession = userSession;
  }

  public void writeFull(JsonWriter json, User user, Collection<String> groups, @Nullable Collection<String> fields) {
    json.beginObject();
    json.prop(FIELD_LOGIN, user.login());
    writeIfNeeded(json, user.name(), FIELD_NAME, fields);
    writeIfNeeded(json, user.email(), FIELD_EMAIL, fields);
    writeIfNeeded(json, user.active(), FIELD_ACTIVE, fields);
    writeGroupsIfNeeded(json, groups, fields);
    writeScmAccountsIfNeeded(json, fields, user);
    json.endObject();
  }

  public void writeConcise(JsonWriter json, @Nullable User user) {
    if (user == null) {
      json.beginObject().endObject();
    } else {
      writeFull(json, user, ImmutableSet.<String>of(), CONCISE_FIELDS);
    }
  }

  private void writeIfNeeded(JsonWriter json, @Nullable String value, String field, @Nullable Collection<String> fields) {
    if (fieldIsWanted(field, fields)) {
      json.prop(field, value);
    }
  }

  private void writeIfNeeded(JsonWriter json, @Nullable Boolean value, String field, @Nullable Collection<String> fields) {
    if (fieldIsWanted(field, fields)) {
      json.prop(field, value);
    }
  }

  private void writeGroupsIfNeeded(JsonWriter json, Collection<String> groups, @Nullable Collection<String> fields) {
    if (fieldIsWanted(FIELD_GROUPS, fields) && userSession.hasGlobalPermission(GlobalPermissions.SYSTEM_ADMIN)) {
      json.name(FIELD_GROUPS).beginArray();
      for (String groupName : groups) {
        json.value(groupName);
      }
      json.endArray();
    }
  }

  private void writeScmAccountsIfNeeded(JsonWriter json, Collection<String> fields, User user) {
    if (fieldIsWanted(FIELD_SCM_ACCOUNTS, fields)) {
      json.name(FIELD_SCM_ACCOUNTS)
        .beginArray()
        .values(((UserDoc) user).scmAccounts())
        .endArray();
    }
  }

  private boolean fieldIsWanted(String field, @Nullable Collection<String> fields) {
    return fields == null || fields.isEmpty() || fields.contains(field);
  }
}
