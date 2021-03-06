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

import com.google.common.collect.Maps;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.ObjectUtils;
import org.apache.commons.lang.StringUtils;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.Scopes;
import org.sonar.api.utils.System2;
import org.sonar.batch.protocol.output.BatchReport;
import org.sonar.core.component.ComponentDto;
import org.sonar.core.component.SnapshotDto;
import org.sonar.core.persistence.DbSession;
import org.sonar.core.util.NonNullInputFunction;
import org.sonar.server.computation.batch.BatchReportReader;
import org.sonar.server.computation.component.Component;
import org.sonar.server.computation.component.DbIdsRepository;
import org.sonar.server.computation.component.TreeRootHolder;
import org.sonar.server.db.DbClient;

/**
 * Persist components and snapshots
 * Also feed the components cache {@link DbIdsRepository}
 */
public class PersistComponentsAndSnapshotsStep implements ComputationStep {

  private final System2 system2;
  private final DbClient dbClient;
  private final TreeRootHolder treeRootHolder;
  private final BatchReportReader reportReader;

  private final DbIdsRepository dbIdsRepositor;

  public PersistComponentsAndSnapshotsStep(System2 system2, DbClient dbClient, TreeRootHolder treeRootHolder, BatchReportReader reportReader, DbIdsRepository dbIdsRepositor) {
    this.system2 = system2;
    this.dbClient = dbClient;
    this.treeRootHolder = treeRootHolder;
    this.reportReader = reportReader;
    this.dbIdsRepositor = dbIdsRepositor;
  }

  @Override
  public void execute() {
    DbSession session = dbClient.openSession(false);
    try {
      org.sonar.server.computation.component.Component root = treeRootHolder.getRoot();
      List<ComponentDto> components = dbClient.componentDao().selectComponentsFromProjectKey(session, root.getKey());
      Map<String, ComponentDto> componentDtosByKey = componentDtosByKey(components);
      PersisComponentExecutor componentContext = new PersisComponentExecutor(session, componentDtosByKey, reportReader, reportReader.readMetadata().getAnalysisDate());

      componentContext.recursivelyProcessComponent(root, null, null);
      session.commit();
    } finally {
      session.close();
    }
  }

  private class PersisComponentExecutor {

    private final BatchReportReader reportReader;
    private final Map<String, ComponentDto> componentDtosByKey;
    private final DbSession dbSession;
    private final long analysisDate;

    private ComponentDto project;
    private SnapshotDto projectSnapshot;

    public PersisComponentExecutor(DbSession dbSession, Map<String, ComponentDto> componentDtosByKey, BatchReportReader reportReader, long analysisDate) {
      this.reportReader = reportReader;
      this.componentDtosByKey = componentDtosByKey;
      this.dbSession = dbSession;
      this.analysisDate = analysisDate;
    }

    private void recursivelyProcessComponent(Component component, @Nullable ComponentDto lastModule, @Nullable SnapshotDto parentSnapshot) {
      BatchReport.Component reportComponent = reportReader.readComponent(component.getRef());

      switch (component.getType()) {
        case PROJECT:
          PersistedComponent persistedProject = processProject(component, reportComponent);
          this.project = persistedProject.componentDto;
          this.projectSnapshot = persistedProject.parentSnapshot;
          processChildren(component, project, persistedProject.parentSnapshot);
          break;
        case MODULE:
          PersistedComponent persistedModule = processModule(component, reportComponent, nonNullLastModule(lastModule), nonNullParentSnapshot(parentSnapshot));
          processChildren(component, persistedModule.componentDto, persistedModule.parentSnapshot);
          break;
        case DIRECTORY:
          PersistedComponent persistedDirectory = processDirectory(component, reportComponent, nonNullLastModule(lastModule), nonNullParentSnapshot(parentSnapshot));
          processChildren(component, nonNullLastModule(lastModule), persistedDirectory.parentSnapshot);
          break;
        case FILE:
          processFile(component, reportComponent, nonNullLastModule(lastModule), nonNullParentSnapshot(parentSnapshot));
          break;
        default:
          throw new IllegalStateException(String.format("Unsupported component type '%s'", component.getType()));
      }
    }

    private void processChildren(Component component, ComponentDto lastModule, SnapshotDto parentSnapshot) {
      for (Component child : component.getChildren()) {
        recursivelyProcessComponent(child, lastModule, parentSnapshot);
      }
    }

    private ComponentDto nonNullLastModule(@Nullable ComponentDto lastModule) {
      return lastModule == null ? project : lastModule;
    }

    private SnapshotDto nonNullParentSnapshot(@Nullable SnapshotDto parentSnapshot) {
      return parentSnapshot == null ? projectSnapshot : parentSnapshot;
    }

    public PersistedComponent processProject(Component project, BatchReport.Component reportComponent) {
      ComponentDto componentDto = createComponentDto(reportComponent, project);

      componentDto.setScope(Scopes.PROJECT);
      componentDto.setQualifier(Qualifiers.PROJECT);
      componentDto.setName(reportComponent.getName());
      componentDto.setLongName(componentDto.name());
      if (reportComponent.hasDescription()) {
        componentDto.setDescription(reportComponent.getDescription());
      }
      componentDto.setProjectUuid(componentDto.uuid());
      componentDto.setModuleUuidPath(ComponentDto.MODULE_UUID_PATH_SEP + componentDto.uuid() + ComponentDto.MODULE_UUID_PATH_SEP);

      ComponentDto projectDto = persistComponent(project.getRef(), componentDto);
      SnapshotDto snapshotDto = persistSnapshot(projectDto, reportComponent.getVersion(), null);

      addToCache(project, projectDto, snapshotDto);

      return new PersistedComponent(projectDto, snapshotDto);
    }

    public PersistedComponent processModule(Component module, BatchReport.Component reportComponent, ComponentDto lastModule, SnapshotDto parentSnapshot) {
      ComponentDto componentDto = createComponentDto(reportComponent, module);

      componentDto.setScope(Scopes.PROJECT);
      componentDto.setQualifier(Qualifiers.MODULE);
      componentDto.setName(reportComponent.getName());
      componentDto.setLongName(componentDto.name());
      if (reportComponent.hasPath()) {
        componentDto.setPath(reportComponent.getPath());
      }
      if (reportComponent.hasDescription()) {
        componentDto.setDescription(reportComponent.getDescription());
      }
      componentDto.setParentProjectId(project.getId());
      componentDto.setProjectUuid(lastModule.projectUuid());
      componentDto.setModuleUuid(lastModule.uuid());
      componentDto.setModuleUuidPath(lastModule.moduleUuidPath() + componentDto.uuid() + ComponentDto.MODULE_UUID_PATH_SEP);

      ComponentDto moduleDto = persistComponent(module.getRef(), componentDto);
      SnapshotDto snapshotDto = persistSnapshot(moduleDto, reportComponent.getVersion(), parentSnapshot);

      addToCache(module, moduleDto, snapshotDto);
      return new PersistedComponent(moduleDto, snapshotDto);
    }

    public PersistedComponent processDirectory(org.sonar.server.computation.component.Component directory, BatchReport.Component reportComponent,
      ComponentDto lastModule, SnapshotDto parentSnapshot) {
      ComponentDto componentDto = createComponentDto(reportComponent, directory);

      componentDto.setScope(Scopes.DIRECTORY);
      componentDto.setQualifier(Qualifiers.DIRECTORY);
      componentDto.setName(reportComponent.getPath());
      componentDto.setLongName(reportComponent.getPath());
      if (reportComponent.hasPath()) {
        componentDto.setPath(reportComponent.getPath());
      }

      componentDto.setParentProjectId(lastModule.getId());
      componentDto.setProjectUuid(lastModule.projectUuid());
      componentDto.setModuleUuid(lastModule.uuid());
      componentDto.setModuleUuidPath(lastModule.moduleUuidPath());

      ComponentDto directoryDto = persistComponent(directory.getRef(), componentDto);
      SnapshotDto snapshotDto = persistSnapshot(directoryDto, null, parentSnapshot);

      addToCache(directory, directoryDto, snapshotDto);
      return new PersistedComponent(directoryDto, snapshotDto);
    }

    public void processFile(org.sonar.server.computation.component.Component file, BatchReport.Component reportComponent,
      ComponentDto lastModule, SnapshotDto parentSnapshot) {
      ComponentDto componentDto = createComponentDto(reportComponent, file);

      componentDto.setScope(Scopes.FILE);
      componentDto.setQualifier(getFileQualifier(reportComponent));
      componentDto.setName(FilenameUtils.getName(reportComponent.getPath()));
      componentDto.setLongName(reportComponent.getPath());
      if (reportComponent.hasPath()) {
        componentDto.setPath(reportComponent.getPath());
      }
      if (reportComponent.hasLanguage()) {
        componentDto.setLanguage(reportComponent.getLanguage());
      }

      componentDto.setParentProjectId(lastModule.getId());
      componentDto.setProjectUuid(lastModule.projectUuid());
      componentDto.setModuleUuid(lastModule.uuid());
      componentDto.setModuleUuidPath(lastModule.moduleUuidPath());

      ComponentDto fileDto = persistComponent(file.getRef(), componentDto);
      SnapshotDto snapshotDto = persistSnapshot(fileDto, null, parentSnapshot);

      addToCache(file, fileDto, snapshotDto);
    }

    private ComponentDto createComponentDto(BatchReport.Component reportComponent, org.sonar.server.computation.component.Component component) {
      String componentKey = component.getKey();
      String componentUuid = component.getUuid();

      ComponentDto componentDto = new ComponentDto();
      componentDto.setUuid(componentUuid);
      componentDto.setKey(componentKey);
      componentDto.setDeprecatedKey(componentKey);
      componentDto.setEnabled(true);
      return componentDto;
    }

    private ComponentDto persistComponent(int componentRef, ComponentDto componentDto) {
      ComponentDto existingComponent = componentDtosByKey.get(componentDto.getKey());
      if (existingComponent == null) {
        dbClient.componentDao().insert(dbSession, componentDto);
        return componentDto;
      } else {
        if (updateComponent(existingComponent, componentDto)) {
          dbClient.componentDao().update(dbSession, existingComponent);
        }
        return existingComponent;
      }
    }

    private SnapshotDto persistSnapshot(ComponentDto componentDto, @Nullable String version, @Nullable SnapshotDto parentSnapshot){
      SnapshotDto snapshotDto = new SnapshotDto();
//        .setRootProjectId(project.getId())
//        .setVersion(version)
//        .setComponentId(componentDto.getId())
//        .setQualifier(componentDto.qualifier())
//        .setScope(componentDto.scope())
//        .setCreatedAt(analysisDate)
//        .setBuildDate(system2.now());
//
//      if (parentSnapshot != null) {
//        snapshotDto
//          .setParentId(parentSnapshot.getId())
//          .setRootId(parentSnapshot.getRootId() == null ? parentSnapshot.getId() : parentSnapshot.getRootId())
//          .setDepth(parentSnapshot.getDepth() + 1)
//          .setPath(parentSnapshot.getPath() + parentSnapshot.getId() + ".");
//      } else {
//        snapshotDto
//          .setPath("")
//          .setDepth(0);
//      }
//      dbClient.snapshotDao().insert(dbSession, snapshotDto);
      return snapshotDto;
    }

    private void addToCache(Component component, ComponentDto componentDto, SnapshotDto snapshotDto) {
      dbIdsRepositor.setComponentId(component, componentDto.getId());
    }

    private boolean updateComponent(ComponentDto existingComponent, ComponentDto newComponent) {
      boolean isUpdated = false;
      if (!StringUtils.equals(existingComponent.name(), newComponent.name())) {
        existingComponent.setName(newComponent.name());
        isUpdated = true;
      }
      if (!StringUtils.equals(existingComponent.description(), newComponent.description())) {
        existingComponent.setDescription(newComponent.description());
        isUpdated = true;
      }
      if (!StringUtils.equals(existingComponent.path(), newComponent.path())) {
        existingComponent.setPath(newComponent.path());
        isUpdated = true;
      }
      if (!StringUtils.equals(existingComponent.moduleUuid(), newComponent.moduleUuid())) {
        existingComponent.setModuleUuid(newComponent.moduleUuid());
        isUpdated = true;
      }
      if (!existingComponent.moduleUuidPath().equals(newComponent.moduleUuidPath())) {
        existingComponent.setModuleUuidPath(newComponent.moduleUuidPath());
        isUpdated = true;
      }
      if (!ObjectUtils.equals(existingComponent.parentProjectId(), newComponent.parentProjectId())) {
        existingComponent.setParentProjectId(newComponent.parentProjectId());
        isUpdated = true;
      }
      return isUpdated;
    }

    private String getFileQualifier(BatchReport.Component reportComponent) {
      return reportComponent.getIsTest() ? Qualifiers.UNIT_TEST_FILE : Qualifiers.FILE;
    }

    private class PersistedComponent {
      private ComponentDto componentDto;
      private SnapshotDto parentSnapshot;

      public PersistedComponent(ComponentDto componentDto, SnapshotDto parentSnapshot) {
        this.componentDto = componentDto;
        this.parentSnapshot = parentSnapshot;
      }
    }

  }

  private static Map<String, ComponentDto> componentDtosByKey(List<ComponentDto> components) {
    return Maps.uniqueIndex(components, new NonNullInputFunction<ComponentDto, String>() {
      @Override
      public String doApply(ComponentDto input) {
        return input.key();
      }
    });
  }

  @Override
  public String getDescription() {
    return "Feed components and snapshots";
  }
}
