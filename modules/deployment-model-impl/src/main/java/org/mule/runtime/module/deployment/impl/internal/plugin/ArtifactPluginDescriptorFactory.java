/*
 * Copyright (c) MuleSoft, Inc.  All rights reserved.  http://www.mulesoft.com
 * The software in this package is published under the terms of the CPAL v1.0
 * license, a copy of which has been included with this distribution in the
 * LICENSE.txt file.
 */

package org.mule.runtime.module.deployment.impl.internal.plugin;

import static java.io.File.separator;
import static java.lang.String.format;
import static org.mule.runtime.api.util.Preconditions.checkArgument;
import static org.mule.runtime.core.config.bootstrap.ArtifactType.PLUGIN;
import static org.mule.runtime.core.util.JarUtils.loadFileContentFrom;
import static org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor.MULE_ARTIFACT_FOLDER;
import static org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor.MULE_PLUGIN_JSON;
import org.mule.runtime.api.deployment.meta.MuleArtifactLoaderDescriptor;
import org.mule.runtime.api.deployment.meta.MulePluginModel;
import org.mule.runtime.api.deployment.persistence.MulePluginModelJsonSerializer;
import org.mule.runtime.core.registry.SpiServiceRegistry;
import org.mule.runtime.deployment.model.api.plugin.ArtifactPluginDescriptor;
import org.mule.runtime.deployment.model.api.plugin.LoaderDescriber;
import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorCreateException;
import org.mule.runtime.module.artifact.descriptor.ArtifactDescriptorFactory;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptor;
import org.mule.runtime.module.artifact.descriptor.BundleDescriptorLoader;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModel;
import org.mule.runtime.module.artifact.descriptor.ClassLoaderModelLoader;
import org.mule.runtime.module.artifact.descriptor.InvalidDescriptorLoaderException;
import org.mule.runtime.module.deployment.impl.internal.artifact.DescriptorLoaderRepository;
import org.mule.runtime.module.deployment.impl.internal.artifact.LoaderNotFoundException;
import org.mule.runtime.module.deployment.impl.internal.artifact.ServiceRegistryDescriptorLoaderRepository;

import java.io.File;
import java.io.IOException;
import java.util.Optional;

/**
 * Creates {@link ArtifactPluginDescriptor} instances
 */
public class ArtifactPluginDescriptorFactory implements ArtifactDescriptorFactory<ArtifactPluginDescriptor> {

  private static final String MULE_PLUGIN_JSON_PATH = MULE_ARTIFACT_FOLDER + separator + MULE_PLUGIN_JSON;

  private final DescriptorLoaderRepository descriptorLoaderRepository;

  /**
   * Creates a default factory
   */
  public ArtifactPluginDescriptorFactory() {
    this(new ServiceRegistryDescriptorLoaderRepository(new SpiServiceRegistry()));
  }

  /**
   * Creates a custom factory
   * 
   * @param descriptorLoaderRepository contains all the {@link ClassLoaderModelLoader} registered on the container. Non null
   */
  public ArtifactPluginDescriptorFactory(DescriptorLoaderRepository descriptorLoaderRepository) {
    checkArgument(descriptorLoaderRepository != null, "descriptorLoaderRepository cannot be null");
    this.descriptorLoaderRepository = descriptorLoaderRepository;
  }

  @Override
  public ArtifactPluginDescriptor create(File pluginJarFile) throws ArtifactDescriptorCreateException {
    try {
      checkArgument(pluginJarFile.isDirectory() || pluginJarFile.getName().endsWith(".jar"),
                    "provided file is not a plugin: " + pluginJarFile.getAbsolutePath());
      Optional<byte[]> jsonDescriptorContentOptional =
          loadFileContentFrom(pluginJarFile, MULE_PLUGIN_JSON_PATH);
      return jsonDescriptorContentOptional
          .map(jsonDescriptorContent -> loadFromJsonDescriptor(pluginJarFile, new String(jsonDescriptorContent)))
          .orElseThrow(() -> new ArtifactDescriptorCreateException(pluginDescriptorNotFound(pluginJarFile)));
    } catch (ArtifactDescriptorCreateException e) {
      throw e;
    } catch (IOException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
  }

  private ArtifactPluginDescriptor loadFromJsonDescriptor(File pluginJarFile, String jsonDescriptorContent) {
    final MulePluginModel mulePluginModel = getMulePluginJsonDescriber(jsonDescriptorContent);

    final ArtifactPluginDescriptor descriptor = new ArtifactPluginDescriptor(mulePluginModel.getName());

    mulePluginModel.getClassLoaderModelLoaderDescriptor().ifPresent(classLoaderModelLoaderDescriptor -> {
      descriptor.setClassLoaderModel(getClassLoaderModel(pluginJarFile, classLoaderModelLoaderDescriptor));
    });

    descriptor.setBundleDescriptor(getBundleDescriptor(pluginJarFile, mulePluginModel));

    mulePluginModel.getExtensionModelLoaderDescriptor().ifPresent(extensionModelDescriptor -> {
      final LoaderDescriber loaderDescriber = new LoaderDescriber(extensionModelDescriptor.getId());
      loaderDescriber.addAttributes(extensionModelDescriptor.getAttributes());
      descriptor.setExtensionModelDescriptorProperty(loaderDescriber);
    });

    return descriptor;
  }

  private BundleDescriptor getBundleDescriptor(File pluginFolder, MulePluginModel mulePluginModel) {
    BundleDescriptorLoader bundleDescriptorLoader;
    try {
      bundleDescriptorLoader =
          descriptorLoaderRepository.get(mulePluginModel.getBundleDescriptorLoader().getId(), PLUGIN,
                                         BundleDescriptorLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidBundleDescriptorLoaderIdError(pluginFolder, mulePluginModel
          .getBundleDescriptorLoader()));
    }

    try {
      return bundleDescriptorLoader.load(pluginFolder, mulePluginModel.getBundleDescriptorLoader().getAttributes());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
  }

  private ClassLoaderModel getClassLoaderModel(File pluginJarFile,
                                               MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor) {
    ClassLoaderModelLoader classLoaderModelLoader;
    try {
      classLoaderModelLoader =
          descriptorLoaderRepository.get(classLoaderModelLoaderDescriptor.getId(), PLUGIN, ClassLoaderModelLoader.class);
    } catch (LoaderNotFoundException e) {
      throw new ArtifactDescriptorCreateException(invalidClassLoaderModelIdError(pluginJarFile,
                                                                                 classLoaderModelLoaderDescriptor));
    }

    final ClassLoaderModel classLoaderModel;
    try {
      classLoaderModel = classLoaderModelLoader.load(pluginJarFile, classLoaderModelLoaderDescriptor.getAttributes());
    } catch (InvalidDescriptorLoaderException e) {
      throw new ArtifactDescriptorCreateException(e);
    }
    return classLoaderModel;
  }

  protected static String pluginDescriptorNotFound(File pluginFile) {
    return format("The plugin descriptor '%s' on plugin file '%s' is not present", MULE_PLUGIN_JSON_PATH, pluginFile);
  }

  protected static String invalidBundleDescriptorLoaderIdError(File pluginFolder,
                                                               MuleArtifactLoaderDescriptor bundleDescriptorLoader) {
    return format("The identifier '%s' for a bundle descriptor loader is not supported (error found while reading plugin '%s')",
                  bundleDescriptorLoader.getId(),
                  pluginFolder.getAbsolutePath());
  }

  protected static String invalidClassLoaderModelIdError(File pluginFolder,
                                                         MuleArtifactLoaderDescriptor classLoaderModelLoaderDescriptor) {
    return format("The identifier '%s' for a class loader model descriptor is not supported (error found while reading plugin '%s')",
                  classLoaderModelLoaderDescriptor.getId(),
                  pluginFolder.getAbsolutePath());
  }

  private MulePluginModel getMulePluginJsonDescriber(String jsonDescriptorContent) {
    return new MulePluginModelJsonSerializer().deserialize(jsonDescriptorContent);
  }
}