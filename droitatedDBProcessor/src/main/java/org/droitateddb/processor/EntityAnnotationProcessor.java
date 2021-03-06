/*
 * Copyright (C) 2014 The droitated DB Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.droitateddb.processor;

import org.droitateddb.builder.provider.ContentProviderBuilder;
import org.droitateddb.builder.provider.SourceContentProviderData;
import org.droitateddb.builder.schema.reader.SchemaReader;
import org.droitateddb.builder.schema.visitor.TypeResolvingVisitor;
import org.droitateddb.builder.schema.writer.BasePackageConstantsWriter;
import org.droitateddb.builder.schema.writer.SchemaWriter;
import org.droitateddb.config.Persistence;
import org.droitateddb.entity.Entity;
import org.droitateddb.hooks.Create;
import org.droitateddb.hooks.DbCreate;
import org.droitateddb.hooks.DbUpdate;
import org.droitateddb.hooks.Update;
import org.droitateddb.manifest.AndroidManifest;
import org.droitateddb.manifest.AndroidManifestAccess;
import org.droitateddb.schema.SchemaConstants;

import java.lang.annotation.Annotation;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;

/**
 * @author Alexander Frank
 * @author Falk Appel
 */
@SupportedAnnotationTypes({"org.droitateddb.entity.Entity", "org.droitateddb.config.Persistence",
        "org.droitateddb.hooks.Update", "org.droitateddb.hooks.Create"})
@SupportedSourceVersion(SourceVersion.RELEASE_8)
public class EntityAnnotationProcessor extends AbstractProcessor {

	private Messager              messager;
	private AndroidManifestAccess androidManifestAccess;

	@Override
	public synchronized void init(final ProcessingEnvironment processingEnv) {
		super.init(processingEnv);
		messager = processingEnv.getMessager();
		androidManifestAccess = new AndroidManifestAccess(processingEnv);
	}

	@Override
	public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {
		if (!roundEnv.processingOver()) {
			try {
				Persistence persistence = checkPersistenceAnnotation(roundEnv);
				if (persistence == null) {
					return true;
				}

				AndroidManifest androidManifest = androidManifestAccess.load();
				String basePackage = persistence.basePackage();
				if (basePackage == null || "".equals(basePackage)) {
					basePackage = androidManifest.getPackageName();
				}
				String generatedPackage = basePackage + "." + SchemaConstants.GENERATED_SUFFIX;
				messager.printMessage(Kind.NOTE, "Use base package " + basePackage);
				androidManifestAccess.initGeneratedPackage(generatedPackage);

				String updateHookName = getHookName(roundEnv, Update.class, DbUpdate.class);
				String createHookName = getHookName(roundEnv, Create.class, DbCreate.class);
				Set<? extends Element> entityAnnotated = roundEnv.getElementsAnnotatedWith(Entity.class);

				generateDbSchema(generatedPackage, entityAnnotated, persistence, updateHookName, createHookName);
				generateContentProviderIfRequested(generatedPackage, entityAnnotated);
				generateBasePackageReference(basePackage);
			} catch (RuntimeException e) {
				messager.printMessage(Kind.ERROR, "Not able to generate DB schema from the annotated entity classes " + e.getMessage());
				throw e;
			} catch (Exception e) {
				messager.printMessage(Kind.ERROR, "Not able to generate DB schema from the annotated entity classes " + e.getMessage());
				throw new IllegalStateException(e);
			}
		}
		return true;
	}

	private void generateBasePackageReference(String basePackage) {
		JavaFileWriter javaFileWriter = new JavaFileWriter(SchemaConstants.BASE_PACKAGE_FILE_PACKAGE, SchemaConstants.BASE_PACKAGE_FILE_NAME, processingEnv);
		BasePackageConstantsWriter basePackageConstantsWriter = new BasePackageConstantsWriter(basePackage);
		javaFileWriter.write(basePackageConstantsWriter.write());
	}

	@Override
    public java.util.Set<String> getSupportedOptions() {
        HashSet<String> validOptions = new HashSet<String>();
        validOptions.add("manifest");
        return validOptions;
    }

	private Persistence checkPersistenceAnnotation(final RoundEnvironment roundEnv) {
		Set<? extends Element> persistenceAnnotated = roundEnv.getElementsAnnotatedWith(Persistence.class);

		if (persistenceAnnotated.size() == 0) {
			return null;
		}

		if (persistenceAnnotated.size() > 1) {
			messager.printMessage(Kind.ERROR, "Only one @Persistence activator is allowed within the project", persistenceAnnotated.iterator().next());
			return null;
		}
		Element persistenceClass = persistenceAnnotated.iterator().next();
		return persistenceClass.getAnnotation(Persistence.class);
	}

	private String getHookName(final RoundEnvironment roundEnv, Class<? extends Annotation> hookAnnotation, Class<?> hookInterface) {
		Set<? extends Element> annotated = roundEnv.getElementsAnnotatedWith(hookAnnotation);
		if (annotated.size() == 0) {
			return null;
		}
		if (annotated.size() > 1) {
            messager.printMessage(Kind.ERROR,
                    "Only one " + hookAnnotation.getCanonicalName() + " hook is allowed with the project", annotated.iterator().next());
			return null;
		}
		Element updateElement = annotated.iterator().next();
		TypeElement typeElement = updateElement.accept(new TypeResolvingVisitor(), null);
		boolean implementsDbUpdate = false;
		for (TypeMirror typeMirror : typeElement.getInterfaces()) {
			if (typeMirror.toString().equals(hookInterface.getCanonicalName())) {
				implementsDbUpdate = true;
			}
		}
		if (!implementsDbUpdate) {
            messager.printMessage(Kind.ERROR,
                    "The " + hookAnnotation + " hook has to implement the " + hookInterface.getCanonicalName() + " interface", updateElement);
			return null;
		}
		return typeElement.getQualifiedName().toString();
	}

    private void generateDbSchema(final String packageName, final Set<? extends Element> entityAnnotated, final Persistence persistence, String
            updateHookName, final String createHookName) {
		SchemaReader schemaReader = new SchemaReader(persistence, updateHookName, createHookName, entityAnnotated, processingEnv.getElementUtils(), messager);
		SchemaWriter schemaWriter = new SchemaWriter(packageName, SchemaConstants.DB, schemaReader.read());

		JavaFileWriter javaFileWriter = new JavaFileWriter(packageName, SchemaConstants.DB, processingEnv);
		javaFileWriter.write(schemaWriter.write());
	}

	private void generateContentProviderIfRequested(final String packageName, final Set<? extends Element> entityAnnotated) throws Exception {
		List<ContentProviderData> providers = new LinkedList<ContentProviderData>();
		for (Element entity : entityAnnotated) {
			Entity entityAnnotation = entity.getAnnotation(Entity.class);
			if (entityAnnotation.contentProvider()) {
				ContentProviderBuilder providerBuilder = new ContentProviderBuilder(packageName, entity, messager);
				SourceContentProviderData providerData = providerBuilder.build();
				JavaFileWriter javaFileWriter = new JavaFileWriter(packageName, providerData.getSimpleName(), processingEnv);
				javaFileWriter.write(providerData.getSource());
				providers.add(providerData);
			}
		}
		androidManifestAccess.addProviders(providers);
	}

    public static void main(final String[] args) {
        // keep eclipse happy
    }

}
