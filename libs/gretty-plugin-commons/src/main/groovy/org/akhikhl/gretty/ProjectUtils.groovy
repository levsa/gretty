/*
 * gretty
 *
 * Copyright 2013  Andrey Hihlovskiy.
 *
 * See the file "license.txt" for copying and usage permission.
 */
package org.akhikhl.gretty

import java.util.regex.Pattern
import org.gradle.api.Project
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileTree
import org.slf4j.Logger
import org.slf4j.LoggerFactory

final class ProjectUtils {

  private static final Logger log = LoggerFactory.getLogger(ProjectUtils)

  private static void addDefaultFastReloadDirs(List<FastReloadStruct> result, Project proj) {
    result.add(new FastReloadStruct(baseDir: proj.webAppDir))
    for(def overlay in proj.gretty.overlays)
      addDefaultFastReloadDirs(result, proj.project(overlay))
  }

  private static void addFastReloadDirs(List<FastReloadStruct> result, Project proj, List fastReloads) {
    for(def f in fastReloads) {
      if(f instanceof Boolean)
        continue
      File baseDir
      def pattern
      def excludesPattern
      if(f instanceof String)
        baseDir = new File(f)
      else if(f instanceof File)
        baseDir = f
      else if(f instanceof Map) {
        f.each { key, value ->
          if(key == 'baseDir')
            baseDir = value
          else if(key == 'pattern')
            pattern = value
          else if(key == 'excludesPattern')
            excludesPattern = value
          else
            log.warn 'Unknown fastReload property: {}', key
        }
        if(!baseDir) {
          log.warn 'fastReload property baseDir is not specified'
          continue
        }
      } else {
        log.warn 'fastReload argument must be String, File or Map'
        continue
      }
      resolveFile(proj, baseDir).each {
        result.add(new FastReloadStruct(baseDir: it, pattern: pattern, excludesPattern: excludesPattern))
      }
    }
  }

  private static void collectFastReloads(List result, Project proj) {
    result.addAll(proj.gretty.fastReload)
    for(def overlay in proj.gretty.overlays.reverse()) {
      overlay = proj.project(overlay)
      if(overlay.extensions.findByName('gretty'))
        collectFastReloads(result, overlay)
    }
  }

  static List<File> collectFilesInOutput(Project project, Object filePattern, boolean searchOverlays = true) {
    List<File> result = []
    def collectIt
    collectIt = { Project proj ->
      proj.sourceSets.main.output.files.each { File sourceDir ->
        def collectInDir
        collectInDir = { File dir ->
          dir.listFiles().each {
            if(it.isFile()) {
              String relPath = sourceDir.toPath().relativize(it.toPath())
              boolean match = (filePattern instanceof java.util.regex.Pattern) ? (relPath =~ filePattern) : (relPath == filePattern)
              if(match)
                result.add(it)
            }
            else
              collectInDir(it)
          }
        }
        collectInDir(sourceDir)
      }
      if(searchOverlays && proj.extensions.findByName('gretty'))
        for(String overlay in proj.gretty.overlays.reverse())
          collectIt(proj.project(overlay))
    }
    collectIt(project)
    log.debug 'collectFilesInOutput filePattern: {}, result: {}', filePattern, result
    return result
  }

  private static Set collectOverlayJars(Project project) {
    Set overlayJars = new HashSet()
    def addOverlayJars // separate declaration from init to enable recursion
    addOverlayJars = { Project proj ->
      if(proj.extensions.findByName('gretty'))
        for(def overlay in proj.gretty.overlays) {
          overlay = proj.project(overlay)
          File archivePath = overlay.tasks.findByName('jar')?.archivePath
          if(archivePath)
            overlayJars.add(archivePath)
          addOverlayJars(overlay) // recursion
        }
    }
    addOverlayJars(project)
    return overlayJars
  }

  static File findFileInOutput(Project project, Object filePattern, boolean searchOverlays = true) {
    List files = collectFilesInOutput(project, filePattern, searchOverlays)
    return files.isEmpty() ? null : files[0]
  }

  static String getContextPath(Project project) {
    String contextPath = project.gretty.contextPath
    if(!contextPath)
      for(def overlay in project.gretty.overlays.reverse()) {
        overlay = project.project(overlay)
        if(overlay.extensions.findByName('gretty')) {
          if(overlay.gretty.contextPath) {
            contextPath = overlay.gretty.contextPath
            break
          }
        } else
          log.warn 'Project {} is not gretty-enabled, could not extract it\'s context path', overlay
      }
    return contextPath
  }

  static Set<URL> getClassPath(Project project, boolean inplace) {
    Set<URL> urls = new LinkedHashSet()
    if(inplace) {
      def addProjectClassPath
      addProjectClassPath = { Project proj ->
        urls.addAll proj.sourceSets.main.output.files.collect { it.toURI().toURL() }
        urls.addAll proj.configurations.runtime.files.collect { it.toURI().toURL() }
        // ATTENTION: order of overlay classpath is important!
        if(proj.extensions.findByName('gretty'))
          for(String overlay in proj.gretty.overlays.reverse())
            addProjectClassPath(proj.project(overlay))
      }
      addProjectClassPath(project)
      for(File overlayJar in collectOverlayJars(project))
        if(urls.remove(overlayJar.toURI().toURL()))
          log.debug '{} is overlay jar, exclude from classpath', overlayJar
      for(File grettyConfigJar in project.configurations.grettyHelperConfig.files)
        if(urls.remove(grettyConfigJar.toURI().toURL()))
          log.debug '{} is gretty-config jar, exclude from classpath', grettyConfigJar
    }
    for(URL url in urls)
      log.debug 'classpath URL: {}', url
    return urls
  }

  static List<FastReloadStruct> getFastReload(Project project, List fastReloads = null) {
    if(fastReloads == null) {
      fastReloads = []
      collectFastReloads(fastReloads, project)
    }
    log.debug 'fastReloads={}', fastReloads
    List<FastReloadStruct> result = []
    if(fastReloads.find { (it instanceof Boolean) && it })
      addDefaultFastReloadDirs(result, project)
    addFastReloadDirs(result, project, fastReloads)
    return result
  }

  static File getFinalWarPath(Project project) {
    project.ext.properties.containsKey('finalWarPath') ? project.ext.finalWarPath : project.tasks.war.archivePath
  }

  static Map getInitParameters(Project project) {
    Map initParams = [:]
    for(def overlay in project.gretty.overlays) {
      overlay = project.project(overlay)
      if(overlay.extensions.findByName('gretty')) {
        for(def e in overlay.gretty.initParameters) {
          def paramValue = e.value
          if(paramValue instanceof Closure)
            paramValue = paramValue()
          initParams[e.key] = paramValue
        }
      } else
        log.warn 'Project {} is not gretty-enabled, could not extract it\'s init parameters', overlay
    }
    for(def e in project.gretty.initParameters) {
      def paramValue = e.value
      if(paramValue instanceof Closure)
        paramValue = paramValue()
      initParams[e.key] = paramValue
    }
    return initParams
  }

  static RealmInfo getRealmInfo(Project project) {
    String realm = project.gretty.realm
    def realmConfigFile = project.gretty.realmConfigFile
    if(realm && realmConfigFile) {
      if(!(realmConfigFile instanceof File))
        realmConfigFile = new File(realmConfigFile)
      if(!realmConfigFile.isAbsolute())
        realmConfigFile = new File(project.webAppDir, realmConfigFile.path)
      return new RealmInfo(realm: realm, realmConfigFile: realmConfigFile.absolutePath)
    }
    for(def overlay in project.gretty.overlays.reverse()) {
      overlay = project.project(overlay)
      if(overlay.extensions.findByName('gretty')) {
        RealmInfo info = getRealmInfo(overlay)
        if(info != null)
          return info
      } else
        log.warn 'Project {} is not gretty-enabled, could not extract jetty realm', overlay
    }
  }

  static String getJettyXmlFile(Project project) {
    String result = resolveFileProperty(project, 'jettyXmlFile', 'jetty.xml')
    if(result != null)
      return result
    String jettyHome = System.getenv('JETTY_HOME')
    if(!jettyHome)
      jettyHome = System.getProperty('jetty.home')
    if(jettyHome != null) {
      File file = new File(jettyHome, 'etc/jetty.xml')
      if(file.exists())
        return file.absolutePath
    }
  }

  static String getJettyEnvXmlFile(Project project) {
    return resolveFileProperty(project, 'jettyEnvXmlFile', 'jetty-env.xml')
  }

  static void prepareExplodedWebAppFolder(Project project) {
    // ATTENTION: overlay copy order is important!
    for(String overlay in project.gretty.overlays) {
      def overlayProject = project.project(overlay)
      project.copy {
        from overlayProject.zipTree(getFinalWarPath(overlayProject))
        into "${project.buildDir}/explodedWebapp"
      }
    }
    project.copy {
      from project.zipTree(project.tasks.war.archivePath)
      into "${project.buildDir}/explodedWebapp"
    }
  }

  static void prepareInplaceWebAppFolder(Project project) {
    // ATTENTION: overlay copy order is important!
    for(String overlay in project.gretty.overlays) {
      Project overlayProject = project.project(overlay)
      prepareInplaceWebAppFolder(overlayProject)
      project.copy {
        from "${overlayProject.buildDir}/inplaceWebapp"
        into "${project.buildDir}/inplaceWebapp"
      }
    }
    project.copy {
      from project.webAppDir
      into "${project.buildDir}/inplaceWebapp"
    }
  }

  static List<File> resolveFile(Project project, file) {
    List<File> result = []
    resolveFile_(result, project, file)
    if(result.isEmpty())
      log.debug 'Could not resolve file \'{}\' in {}', file, project
    return result
  }

  private static void resolveFile_(List<File> result, Project project, file) {
    if(!(file instanceof File))
      file = new File(file)
    if(file.isAbsolute()) {
      result.add(file.absoluteFile)
      return
    }
    File f = new File(project.projectDir, file.path)
    if(f.exists())
      result.add(f.absoluteFile)
    f = new File(new File(project.webAppDir, 'WEB-INF'), file.path)
    if(f.exists())
      result.add(f.absoluteFile)
    result.addAll(collectFilesInOutput(project, file.path, false))
    for(def overlay in project.gretty.overlays.reverse())
      resolveFile_(result, project.project(overlay), file)
  }

  static String resolveFileProperty(Project project, String propertyName, defaultValue = null) {
    def file = project.gretty[propertyName]
    if(file == null)
      file = defaultValue
    if(file != null) {
      if(!(file instanceof File))
        file = new File(file)
      if(file.isAbsolute())
        return file.absolutePath
      File f = new File(project.projectDir, file.path)
      if(f.exists())
        return f.absolutePath
      f = new File(new File(project.webAppDir, 'WEB-INF'), file.path)
      if(f.exists())
        return f.absolutePath
      f = findFileInOutput(project, file.path, false)
      if(f != null && f.exists())
        return f.absolutePath
    }
    for(def overlay in project.gretty.overlays.reverse()) {
      overlay = project.project(overlay)
      if(overlay.extensions.findByName('gretty')) {
        String s = resolveFileProperty(overlay, propertyName, defaultValue)
        if(s)
          return s
      } else
        log.warn 'Project {} is not gretty-enabled, could not extract \'{}\'', overlay, propertyName
    }
    if(project.gretty[propertyName] != null && project.gretty[propertyName] != defaultValue)
      log.warn 'Could not find file \'{}\' specified in \'{}\'', file.path, propertyName
    return null
  }

  static File resolveSingleFile(Project project, file) {
    List<File> list = resolveFile(project, file)
    list ? list[0] : null
  }
}
