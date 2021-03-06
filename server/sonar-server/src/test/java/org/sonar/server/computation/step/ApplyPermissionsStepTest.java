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
package org.sonar.server.computation.step;

import java.util.List;
import java.util.Map;
import org.elasticsearch.search.SearchHit;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.sonar.api.config.Settings;
import org.sonar.api.security.DefaultGroups;
import org.sonar.api.utils.System2;
import org.sonar.api.web.UserRole;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.permission.PermissionFacade;
import org.sonar.core.permission.PermissionTemplateDao;
import org.sonar.core.permission.PermissionTemplateDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.persistence.DbTester;
import org.sonar.core.resource.ResourceDao;
import org.sonar.core.user.GroupRoleDto;
import org.sonar.core.user.RoleDao;
import org.sonar.server.component.ComponentTesting;
import org.sonar.server.component.db.ComponentDao;
import org.sonar.server.computation.batch.TreeRootHolderRule;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DbIdsRepository;
import org.sonar.server.computation.component.DumbComponent;
import org.sonar.server.db.DbClient;
import org.sonar.server.es.EsTester;
import org.sonar.server.issue.index.IssueAuthorizationIndexer;
import org.sonar.server.issue.index.IssueIndexDefinition;
import org.sonar.test.DbTests;

import static org.assertj.core.api.Assertions.assertThat;

@Category(DbTests.class)
public class ApplyPermissionsStepTest extends BaseStepTest {

  private static final String PROJECT_KEY = "PROJECT_KEY";
  private static final String PROJECT_UUID = "PROJECT_UUID";

  @ClassRule
  public static EsTester esTester = new EsTester().addDefinitions(new IssueIndexDefinition(new Settings()));

  @ClassRule
  public static DbTester dbTester = new DbTester();

  @Rule
  public TreeRootHolderRule treeRootHolder = new TreeRootHolderRule();

  DbSession dbSession;

  DbClient dbClient;

  Settings settings;

  DbIdsRepository dbIdsRepository;

  IssueAuthorizationIndexer issueAuthorizationIndexer;
  ApplyPermissionsStep step;

  @Before
  public void setUp() throws Exception {
    dbTester.truncateTables();
    esTester.truncateIndices();

    RoleDao roleDao = new RoleDao();
    PermissionTemplateDao permissionTemplateDao = new PermissionTemplateDao(dbTester.myBatis(), System2.INSTANCE);
    dbClient = new DbClient(dbTester.database(), dbTester.myBatis(), new ComponentDao(), roleDao, permissionTemplateDao);
    dbSession = dbClient.openSession(false);

    settings = new Settings();

    issueAuthorizationIndexer = new IssueAuthorizationIndexer(dbClient, esTester.client());
    issueAuthorizationIndexer.setEnabled(true);

    dbIdsRepository = new DbIdsRepository();

    step = new ApplyPermissionsStep(dbClient, dbIdsRepository, issueAuthorizationIndexer, new PermissionFacade(roleDao, null,
      new ResourceDao(dbTester.myBatis(), System2.INSTANCE), permissionTemplateDao, settings), treeRootHolder);
  }

  @After
  public void tearDown() throws Exception {
    dbSession.close();
  }

  @Test
  public void grant_permission() throws Exception {
    ComponentDto projectDto = ComponentTesting.newProjectDto(PROJECT_UUID).setKey(PROJECT_KEY);
    dbClient.componentDao().insert(dbSession, projectDto);

    // Create a permission template containing browse permission for anonymous group
    PermissionTemplateDto permissionTemplateDto = dbClient.permissionTemplateDao().createPermissionTemplate("Default", null, null);
    settings.setProperty("sonar.permission.template.default", permissionTemplateDto.getKee());
    dbClient.permissionTemplateDao().addGroupPermission(permissionTemplateDto.getId(), null, UserRole.USER);
    dbSession.commit();

    Component project = new DumbComponent(Component.Type.PROJECT, 1, PROJECT_UUID, PROJECT_KEY);
    dbIdsRepository.setComponentId(project, projectDto.getId());
    treeRootHolder.setRoot(project);

    step.execute();
    dbSession.commit();

    assertThat(dbClient.componentDao().selectByKey(dbSession, PROJECT_KEY).getAuthorizationUpdatedAt()).isNotNull();
    assertThat(dbClient.roleDao().selectGroupPermissions(dbSession, DefaultGroups.ANYONE, projectDto.getId())).containsOnly(UserRole.USER);
    List<SearchHit> issueAuthorizationHits = esTester.getDocuments(IssueIndexDefinition.INDEX, IssueIndexDefinition.TYPE_AUTHORIZATION);
    assertThat(issueAuthorizationHits).hasSize(1);
    Map<String, Object> issueAhutorization = issueAuthorizationHits.get(0).sourceAsMap();
    assertThat(issueAhutorization.get("project")).isEqualTo(PROJECT_UUID);
    assertThat((List<String>) issueAhutorization.get("groups")).containsOnly(DefaultGroups.ANYONE);
    assertThat((List<String>) issueAhutorization.get("users")).isEmpty();
  }

  @Test
  public void nothing_to_do() throws Exception {
    long authorizationUpdatedAt = 1000L;

    ComponentDto projectDto = ComponentTesting.newProjectDto(PROJECT_UUID).setKey(PROJECT_KEY).setAuthorizationUpdatedAt(authorizationUpdatedAt);
    dbClient.componentDao().insert(dbSession, projectDto);
    // Permissions are already set on the project
    dbClient.roleDao().insertGroupRole(new GroupRoleDto().setRole(UserRole.USER).setGroupId(null).setResourceId(projectDto.getId()), dbSession);

    dbSession.commit();

    Component project = new DumbComponent(Component.Type.PROJECT, 1, PROJECT_UUID, PROJECT_KEY);
    dbIdsRepository.setComponentId(project, projectDto.getId());
    treeRootHolder.setRoot(project);

    step.execute();
    dbSession.commit();

    // Check that authorization updated at has not been changed -> Nothing has been done
    assertThat(projectDto.getAuthorizationUpdatedAt()).isEqualTo(authorizationUpdatedAt);
  }

  @Override
  protected ComputationStep step() {
    return step;
  }
}
