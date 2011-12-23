/*
 * Copyright (c) 2011 Evolveum
 *
 * The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.opensource.org/licenses/cddl1 or
 * CDDLv1.0.txt file in the source code distribution.
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 *
 * Portions Copyrighted 2011 [name of copyright owner]
 */

package com.evolveum.midpoint.schema.processor;

import static com.evolveum.midpoint.schema.processor.ProcessorConstants.*;
import static javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.Validate;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.evolveum.midpoint.schema.constants.SchemaConstants;
import com.evolveum.midpoint.schema.exception.SchemaException;
import com.evolveum.midpoint.util.DOMUtil;
import com.evolveum.midpoint.util.logging.Trace;
import com.evolveum.midpoint.util.logging.TraceManager;
import com.evolveum.midpoint.xml.ns._public.common.common_1.AccessType;
import com.sun.xml.xsom.XSAnnotation;
import com.sun.xml.xsom.XSComplexType;
import com.sun.xml.xsom.XSContentType;
import com.sun.xml.xsom.XSElementDecl;
import com.sun.xml.xsom.XSModelGroup;
import com.sun.xml.xsom.XSParticle;
import com.sun.xml.xsom.XSSchemaSet;
import com.sun.xml.xsom.XSTerm;
import com.sun.xml.xsom.XSType;
import com.sun.xml.xsom.parser.XSOMParser;
import com.sun.xml.xsom.util.DomAnnotationParserFactory;

/**
 * Parser for DOM-represented XSD, creates midPoint Schema representation.
 * 
 * It will parse schema in several passes. It has special handling if the schema is "resource schema", which will
 * create ResourceObjectDefinition and ResourceObjectAttributeDefinition instead of PropertyContainer and Property. 
 * 
 * @author lazyman
 * @author Radovan Semancik
 */
class DomToSchemaProcessor {

	private static final Trace LOGGER = TraceManager.getTrace(DomToSchemaProcessor.class);
	
	private Schema schema;
	
	/**
	 * Main entry point.
	 * 
	 * 
	 * @param xsdSchema DOM-represented XSD schema
	 * @param isResourceSchema true if this is "resource schema"
	 * @return parsed midPoint schema
	 * @throws SchemaException in case of any error
	 */
	Schema parseDom(Element xsdSchema) throws SchemaException {
		Validate.notNull(xsdSchema, "XSD schema element must not be null.");
		
		schema = initSchema(xsdSchema);

		XSSchemaSet set = parseSchema(xsdSchema);
		if (set == null) {
			return schema;
		}
		
		// Create ComplexTypeDefinitions from all top-level complexType definition in the XSD
		createComplexTypeDefinitions(set);

		// Create PropertyContainer (and possibly also Property) definition from the top-level elements in XSD
		// This also creates ResourceObjectDefinition in some cases
		createDefinitionsFromElements(set);
				
		return schema;
	}

	private Schema initSchema(Element xsdSchema) throws SchemaException {
		String targetNamespace = xsdSchema.getAttributeNS(DOMUtil.XSD_ATTR_TARGET_NAMESPACE.getNamespaceURI(),
				DOMUtil.XSD_ATTR_TARGET_NAMESPACE.getLocalPart());
		if (StringUtils.isEmpty(targetNamespace)) {
			// also try without the namespace
			targetNamespace = xsdSchema.getAttribute(DOMUtil.XSD_ATTR_TARGET_NAMESPACE.getLocalPart());
		}
		if (StringUtils.isEmpty(targetNamespace)) {
			throw new SchemaException("Schema does not have targetNamespace specification");
		}
		return new Schema(targetNamespace);
	}
	
	private XSSchemaSet parseSchema(Element schema) throws SchemaException {
		XSSchemaSet xss = null;
		try {
			TransformerFactory transfac = TransformerFactory.newInstance();
			Transformer trans = transfac.newTransformer();
			trans.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
			trans.setOutputProperty(OutputKeys.INDENT, "yes");

			DOMSource source = new DOMSource(schema);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			StreamResult result = new StreamResult(out);

			trans.transform(source, result);

			XSOMParser parser = createSchemaParser();
			InputSource inSource = new InputSource(new ByteArrayInputStream(out.toByteArray()));
			// XXX: hack: it's here to make entity resolver work...
			inSource.setSystemId("SystemId");
			// XXX: end hack
			inSource.setEncoding("utf-8");
			
			parser.parse(inSource);
			
			xss = parser.getResult();
		} catch (Exception ex) {
			throw new SchemaException("Uknown error during resource xsd schema parsing: "
					+ ex.getMessage(), ex);
		}

		return xss;
	}
	
	private XSOMParser createSchemaParser() {
		XSOMParser parser = new XSOMParser();
		SchemaHandler errorHandler = new SchemaHandler(SchemaConstants.getEntityResolver());
		parser.setErrorHandler(errorHandler);
		parser.setAnnotationParser(new DomAnnotationParserFactory());
		parser.setEntityResolver(errorHandler);

		return parser;
	}
	
	/**
	 * Create ComplexTypeDefinitions from all top-level complexType definitions in the XSD.
	 * 
	 * These definitions are the reused later to fit inside PropertyContainer definitions.
	 * 
	 * @param set XS Schema Set
	 */
	private void createComplexTypeDefinitions(XSSchemaSet set) {
		Iterator<XSComplexType> iterator = set.iterateComplexTypes();
		while (iterator.hasNext()) {
			XSComplexType complexType = iterator.next();
			if (complexType.getTargetNamespace().equals(schema.getNamespace())) {
				
				boolean isResourceObject = isResourceObject(complexType);
				
				// Create the actual definition. This is potentially recursive call
				ComplexTypeDefinition complexTypeDefinition = createComplexTypeDefinition(complexType, isResourceObject);				
				schema.getDefinitions().add(complexTypeDefinition);

				if (isResourceObject) {
					// Create ResourceObjectDefinition from all the top-level complexType definitions in XSD that have resourceObject annotation.
					// This is almost a special case for parsing resource schemas. In resource schemas we assume that every annotated 
					// top-level complex type represents a resource object type, therefore is transformed to a ResourceObjectDefinition.

					XSAnnotation annotation = complexType.getAnnotation();
					PropertyContainerDefinition pcd = createPropertyContainerDefinition(complexType, null, complexTypeDefinition, annotation, true);
					schema.getDefinitions().add(pcd);
				}

				
			} else if (complexType.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)) {
				// This is OK to ignore. These are imported data types
//			} else {
//				throw new SchemaException("Found complexType "+complexType.getName()+" with wrong namespace "+complexType.getTargetNamespace()+" while expecting "+schema.getNamespace());
			}
		}
	}

	/**
	 * Creates ComplexTypeDefinition object from a single XSD complexType definition.
	 * @param complexType XS complex type definition
	 * @return
	 */
	private ComplexTypeDefinition createComplexTypeDefinition(XSComplexType complexType, boolean isResourceObject) {
		QName typeName = new QName(complexType.getTargetNamespace(),complexType.getName());
		ComplexTypeDefinition ctd = new ComplexTypeDefinition(null, typeName, schema.getNamespace());
				
		XSContentType content = complexType.getContentType();
		if (content != null) {
			XSParticle particle = content.asParticle();
			if (particle != null) {
				XSTerm term = particle.getTerm();

				if (term.isModelGroup()) {
					addPropertyDefinitionListFromGroup(term.asModelGroup(), ctd, isResourceObject);
				}
			}
		}
		
		return ctd;

	}

	/**
	 * Creates ComplexTypeDefinition object from a XSModelGroup inside XSD complexType definition.
	 * This is a recursive method. It can create "anonymous" internal PropertyContainerDefinitions.
	 * The definitions will be added to the ComplexTypeDefinition provided as parameter.
	 * @param group XSD XSModelGroup
	 * @param ctd ComplexTypeDefinition that will hold the definitions
	 */
	private void addPropertyDefinitionListFromGroup(XSModelGroup group, ComplexTypeDefinition ctd, boolean isResourceObject) {

		XSParticle[] particles = group.getChildren();
		for (XSParticle p : particles) {
			XSTerm pterm = p.getTerm();
			if (pterm.isModelGroup()) {
				addPropertyDefinitionListFromGroup(pterm.asModelGroup(), ctd, isResourceObject);
			}

			// xs:element inside complex type
			if (pterm.isElementDecl()) {
				XSAnnotation annotation = selectAnnotationToUse(p.getAnnotation(), pterm.getAnnotation());

				XSElementDecl element = pterm.asElementDecl();
				QName elementName = new QName(element.getTargetNamespace(), element.getName());
				
				XSType xsType = element.getType();
				
				if (isObjectDefinition(xsType)) {					
					// This is object reference. It also has its *Ref equivalent which will get parsed.
					// therefore it is safe to ignore
					
				} else if (isPropertyContainer(xsType)) {

					// Create an inner PropertyContainer. It is assumed that this is a XSD complex type
					// TODO: check cast
					XSComplexType complexType = (XSComplexType)xsType;
					ComplexTypeDefinition innerComplexTypeDefinition = createComplexTypeDefinition(complexType, false);
					XSAnnotation containerAnnotation = complexType.getAnnotation();
					PropertyContainerDefinition containerDefinition = createPropertyContainerDefinition(xsType,elementName,innerComplexTypeDefinition,containerAnnotation,false);
					ctd.getDefinitions().add(containerDefinition);
					
				} else if (xsType.getName() == null) {
					// Anonymous types. Safe to ignore?
					// xsd:any will fall here as well ...
					
//					System.out.println("SSSSSSSS no type name for element: "+elementName);
//					System.out.println("SSSSSSSS element: "+element);
//					System.out.println("SSSSSSSS element wildcard: "+element.isWildcard());
//					System.out.println("SSSSSSSS type: "+xsType);
//					System.out.println("SSSSSSSS base type: "+xsType.getBaseType());
//					System.out.println("SSSSSSSS base type name: "+xsType.getBaseType().getName());
					
										
					if (isAny(xsType)) {
						// This is a element with xsd:any type. It has to be property container
						XSAnnotation containerAnnotation = xsType.getAnnotation();
						PropertyContainerDefinition containerDefinition = createPropertyContainerDefinition(xsType,elementName,null,containerAnnotation,false);
						ctd.getDefinitions().add(containerDefinition);
					}
					
				} else {
										
					// Create a property definition (even if this is a XSD complex type)
					QName typeName = new QName(xsType.getTargetNamespace(), xsType.getName());

					PropertyDefinition propDef = createPropertyDefinition(xsType, elementName, typeName, annotation, isResourceObject);
					
					propDef.setMinOccurs(p.getMinOccurs());
					propDef.setMaxOccurs(p.getMaxOccurs());
					
					ctd.getDefinitions().add(propDef);
				}
			}
		}
	}
	

	/**
	 * Create PropertyContainer (and possibly also Property) definition from the top-level elements in XSD.
	 * Each top-level element will be interpreted as a potential PropertyContainer. The element name will be set as
	 * name of the PropertyContainer, element type will become type (indirectly through ComplexTypeDefinition).
	 * 
	 * No need to recurse here. All the work was already done while creating ComplexTypeDefinitions.
	 * 
	 * @param set XS Schema Set
	 * @throws SchemaException
	 */
	private void createDefinitionsFromElements(XSSchemaSet set) throws SchemaException {
		Iterator<XSElementDecl> iterator = set.iterateElementDecls();
		while (iterator.hasNext()) {
			XSElementDecl xsElementDecl = iterator.next();
			if (xsElementDecl.getTargetNamespace().equals(schema.getNamespace())) {
				
				QName elementName = new QName(xsElementDecl.getTargetNamespace(),xsElementDecl.getName());
				XSType xsType = xsElementDecl.getType();
				if (xsType == null) {
					throw new SchemaException("Found element "+elementName+" without type definition");
				}
				if (xsType.getName() == null) {
					// No type defined, safe to skip
					continue;
					//throw new SchemaException("Found element "+elementName+" with incomplete type name: {"+xsType.getTargetNamespace()+"}"+xsType.getName());
				}
				QName typeQName = new QName (xsType.getTargetNamespace(),xsType.getName());
				XSAnnotation annotation = xsType.getAnnotation();
				
				if (isPropertyContainer(xsType)) {
					
					ComplexTypeDefinition complexTypeDefinition = schema.findComplexTypeDefinition(typeQName);
					PropertyContainerDefinition propertyContainerDefinition = createPropertyContainerDefinition(xsType, elementName, complexTypeDefinition, annotation, false);
					schema.getDefinitions().add(propertyContainerDefinition);
					
				} else {
					
					// Create a top-level property definition (even if this is a XSD complex type)
					// This is not really useful, just for the sake of completeness
					QName typeName = new QName(xsType.getTargetNamespace(), xsType.getName());
					PropertyDefinition propDef = createPropertyDefinition(xsType, elementName, typeName, annotation, false);
					schema.getDefinitions().add(propDef);
				}
				
			} else if (xsElementDecl.getTargetNamespace().equals(XMLConstants.W3C_XML_SCHEMA_NS_URI)) {
				// This is OK to ignore. These are imported elements from other schemas
//				} else {
//					throw new SchemaException("Found element "+xsElementDecl.getName()+" with wrong namespace "+xsElementDecl.getTargetNamespace()+" while expecting "+schema.getNamespace());
			}
		}
	}
	
	private boolean isAny(XSType xsType) {
		// FIXME: Not perfect and probably quite wrong. But works for now.
		if (xsType.getName() == null && xsType.getBaseType().getName().equals("anyType")) {
			return true;
		}
		return false;
	}
	
	/**
	 * Returns true if provides XSD type is a property container. It looks for annotations.
	 */
	private boolean isPropertyContainer(XSType xsType) {
		Element annoElement = getAnnotationElement(xsType.getAnnotation(), ProcessorConstants.A_PROPERTY_CONTAINER);
		if (annoElement != null) {
			return true;
		}
		if (xsType.getBaseType() != null && !xsType.getBaseType().equals(xsType)) {
			return isPropertyContainer(xsType.getBaseType());
		}
		return false;
	}
	
	/**
	 * Returns true if provides XSD type is an object definition. It looks for a ObjectType supertype.
	 */
	private boolean isObjectDefinition(XSType xsType) {
		if (xsType.getName() == null) {
			return false;
		}
		QName typeName = new QName(xsType.getTargetNamespace(),xsType.getName());
		if (typeName.equals(SchemaConstants.C_OBJECT_TYPE)) {
			return true;
		}
		if (xsType.getBaseType() != null && !xsType.getBaseType().equals(xsType)) {
			return isObjectDefinition(xsType.getBaseType());
		}
		return false;
	}
	
	private boolean isResourceObject(XSComplexType complexType) {
		XSAnnotation annotation = complexType.getAnnotation();
		// annotation: resourceObject
		if (getAnnotationElement(annotation, A_RESOURCE_OBJECT) != null) {
			return true;
		}
		// annotation: accountType
		if (getAnnotationElement(annotation, A_ACCOUNT_TYPE) != null) {
			// <accountType> implies <resourceObject> ... at least for now (compatibility)
			return true;
		}
		return false;
	}


	/**
	 * Creates appropriate instance of PropertyContainerDefinition.
	 * It may be PropertyContainerDefinition itself or one of its subclasses (ResourceObjectDefinition). This method also takes care of parsing
	 * all the annotations and similar fancy stuff.
	 * 
	 * We need to pass createResourceObject flag explicitly here. Because even if we are in resource schema, we want PropertyContainers inside
	 * ResourceObjects, not ResourceObjects inside ResouceObjects.
	 * 
	 * @param xsType
	 * @param elementName name of the created definition
	 * @param complexTypeDefinition complex type to use for the definition.
	 * @param annotation XS annotation to apply.
	 * @param createResourceObject true if ResourceObjectDefinition should be created
	 * @return appropriate PropertyContainerDefinition instance
	 */
	private PropertyContainerDefinition createPropertyContainerDefinition(XSType xsType, QName elementName, ComplexTypeDefinition complexTypeDefinition, XSAnnotation annotation, boolean createResourceObject) {
		PropertyContainerDefinition pcd;
		
		if (createResourceObject) {
			ResourceObjectDefinition rod = new ResourceObjectDefinition(schema, elementName, complexTypeDefinition);
			
			// Parse resource-specific annotations
			
			// nativeObjectClass
			Element nativeAttrElement = getAnnotationElement(annotation, A_NATIVE_OBJECT_CLASS);
			String nativeObjectClass = nativeAttrElement == null ? null : nativeAttrElement.getTextContent();
			rod.setNativeObjectClass(nativeObjectClass);
			
			// accountType
			if (isAccountObject(annotation)) {
				rod.setAccountType(true);
			}
			// check if it's default account object class...
			Element accountType = getAnnotationElement(annotation, A_ACCOUNT_TYPE);
			if (accountType != null) {
				String defaultValue = accountType.getAttribute("default");
				if (defaultValue != null) {
					rod.setDefaultAccountType(Boolean.parseBoolean(defaultValue));
				}
			}
			
			// displayName
			ResourceObjectAttributeDefinition attrDefinition = getAnnotationReference(annotation, A_DISPLAY_NAME, rod);
			if (attrDefinition != null) {
				rod.setDisplayNameAttribute(attrDefinition);
			}
			// namingAttribute
			attrDefinition = getAnnotationReference(annotation, A_NAMING_ATTRIBUTE, rod);
			if (attrDefinition != null) {
				rod.setNamingAttribute(attrDefinition);
			}
			// descriptionAttribute
			attrDefinition = getAnnotationReference(annotation, A_DESCRIPTION_ATTRIBUTE, rod);
			if (attrDefinition != null) {
				rod.setDescriptionAttribute(attrDefinition);
			}
			// identifier
			attrDefinition = getAnnotationReference(annotation, A_IDENTIFIER, rod);
			if (attrDefinition != null) {
				rod.getIdentifiers().add(attrDefinition);
			}
			// secondaryIdentifier
			attrDefinition = getAnnotationReference(annotation, A_SECONDARY_IDENTIFIER, rod);
			if (attrDefinition != null) {
				rod.getSecondaryIdentifiers().add(attrDefinition);
			}

			
			pcd = rod;
		} else {
			if (isObjectDefinition(xsType)) {
				pcd = new ObjectDefinition(elementName, complexTypeDefinition);
			} else {
				pcd = new PropertyContainerDefinition(elementName, complexTypeDefinition);
			}
		}
		
		// TODO: parse generic annotations
		
		// ignore		
		List<Element> ignore = getAnnotationElements(annotation, A_IGNORE);
		if (ignore != null && !ignore.isEmpty()) {
			if (ignore.size() != 1) {
				// TODO: error!
			}
			String ignoreString = ignore.get(0).getTextContent();
			if (StringUtils.isEmpty(ignoreString)) {
				// Element is present but no content: defaults to "true"
				pcd.setIgnored(true);
			} else {
				pcd.setIgnored(Boolean.parseBoolean(ignoreString));
			}
		}
		
		return pcd;
	}
	
	/**
	 * Creates appropriate instance of PropertyDefinition.
	 * It creates either PropertyDefinition itself or one of its subclasses (ResourceObjectAttributeDefinition). The behavior
	 * depends of the "mode" of the schema. This method is also processing annotations and other fancy property-relates stuff.
	 * 
	 * @param xsType
	 * @param elementName
	 * @param annotation
	 * @return
	 */
	private PropertyDefinition createPropertyDefinition(XSType xsType, QName elementName, QName typeName, XSAnnotation annotation, boolean isResourceObject) {
		PropertyDefinition prodDef;
		if (isResourceObject) {
			ResourceObjectAttributeDefinition attrDef = new ResourceObjectAttributeDefinition(elementName, null, typeName);
			
			// Process Resource-specific annotations
			
			// nativeAttributeName
			Element nativeAttrElement = getAnnotationElement(annotation, A_NATIVE_ATTRIBUTE_NAME);
			String nativeAttributeName = nativeAttrElement == null ? null : nativeAttrElement.getTextContent();
			if (!StringUtils.isEmpty(nativeAttributeName)) {
				attrDef.setNativeAttributeName(nativeAttributeName);
			}
						
			prodDef = attrDef;
		} else {
			prodDef = new PropertyDefinition(elementName, typeName);
		}
		
		// Process generic annotations
		
		if (annotation == null || annotation.getAnnotation() == null) {
			return prodDef;
		}
		
		// ignore		
		List<Element> ignore = getAnnotationElements(annotation, A_IGNORE);
		if (ignore != null && !ignore.isEmpty()) {
			if (ignore.size() != 1) {
				// TODO: error!
			}
			String ignoreString = ignore.get(0).getTextContent();
			if (StringUtils.isEmpty(ignoreString)) {
				// Element is present but no content: defaults to "true"
				prodDef.setIgnored(true);
			} else {
				prodDef.setIgnored(Boolean.parseBoolean(ignoreString));
			}
		}
		
		// access
		List<Element> accessList = getAnnotationElements(annotation, A_ACCESS);
		if (accessList != null && !accessList.isEmpty()) {
			prodDef.setCreate(containsAccessFlag("create", accessList));
			prodDef.setRead(containsAccessFlag("read", accessList));
			prodDef.setUpdate(containsAccessFlag("update", accessList));
		}
		
		// attributeDisplayName
		Element attributeDisplayName = getAnnotationElement(annotation, A_ATTRIBUTE_DISPLAY_NAME);
		if (attributeDisplayName != null) {
			prodDef.setDisplayName(attributeDisplayName.getTextContent());
		}
		
		// help
		Element help = getAnnotationElement(annotation, A_HELP);
		if (help != null) {
			prodDef.setHelp(help.getTextContent());
		}
		
		List<Element> accessElements = getAnnotationElements(annotation, A_ACCESS);
		if (accessElements == null || accessElements.isEmpty()) {
			// Default access is read-write-create
			prodDef.setCreate(true);
			prodDef.setUpdate(true);
			prodDef.setRead(true);
		} else {
			prodDef.setCreate(false);
			prodDef.setUpdate(false);
			prodDef.setRead(false);
			for (Element e : accessElements) {
				String access = e.getTextContent();
				if (access.equals(AccessType.CREATE.value())) {
					prodDef.setCreate(true);
				}
				if (access.equals(AccessType.UPDATE.value())) {
					prodDef.setUpdate(true);
				}
				if (access.equals(AccessType.READ.value())) {
					prodDef.setRead(true);
				}
			}
		}
		
		return prodDef;
	}
	
	
	private Element getAnnotationElement(XSAnnotation annotation, QName qname) {
		List<Element> elements = getAnnotationElements(annotation, qname);
		if (elements.isEmpty()) {
			return null;
		}

		return elements.get(0);
	}


	private boolean containsAccessFlag(String flag, List<Element> accessList) {
		for (Element element : accessList) {
			if (flag.equals(element.getTextContent())) {
				return true;
			}
		}

		return false;
	}

	private ResourceObjectAttributeDefinition getAnnotationReference(XSAnnotation annotation, QName qname, ResourceObjectDefinition objectClass) {
		Element element = getAnnotationElement(annotation, qname);
		if (element != null) {
			String reference = element.getAttribute("ref");
			if (reference != null && !reference.isEmpty()) {				
				PropertyDefinition definition = objectClass.findPropertyDefinition(DOMUtil.resolveQName(element, reference));
				if (definition instanceof ResourceObjectAttributeDefinition) {
					return (ResourceObjectAttributeDefinition) definition;
				}
			}
		}

		return null;
	}

	private boolean isAccountObject(XSAnnotation annotation) {
		if (annotation == null || annotation.getAnnotation() == null) {
			return false;
		}

		Element accountType = getAnnotationElement(annotation, A_ACCOUNT_TYPE);
		if (accountType != null) {
			return true;
		}

		return false;
	}

	private List<Element> getAnnotationElements(XSAnnotation annotation, QName qname) {
		List<Element> elements = new ArrayList<Element>();
		if (annotation == null) {
			return elements;
		}

		Element xsdAnnotation = (Element) annotation.getAnnotation();
		NodeList list = xsdAnnotation.getElementsByTagNameNS(qname.getNamespaceURI(), qname.getLocalPart());
		if (list != null && list.getLength() > 0) {
			for (int i = 0; i < list.getLength(); i++) {
				elements.add((Element) list.item(i));
			}
		}

		return elements;
	}


	private XSAnnotation selectAnnotationToUse(XSAnnotation particleAnnotation, XSAnnotation termAnnotation) {
		boolean useParticleAnnotation = false;
		if (particleAnnotation != null && particleAnnotation.getAnnotation() != null) {
			if (testAnnotationAppinfo(particleAnnotation)) {
				useParticleAnnotation = true;
			}
		}

		boolean useTermAnnotation = false;
		if (termAnnotation != null && termAnnotation.getAnnotation() != null) {
			if (testAnnotationAppinfo(termAnnotation)) {
				useTermAnnotation = true;
			}
		}

		if (useParticleAnnotation) {
			return particleAnnotation;
		}

		if (useTermAnnotation) {
			return termAnnotation;
		}

		return null;
	}

	private boolean testAnnotationAppinfo(XSAnnotation annotation) {
		Element appinfo = getAnnotationElement(annotation, new QName(W3C_XML_SCHEMA_NS_URI, "appinfo"));
		if (appinfo != null) {
			return true;
		}

		return false;
	}


	
}
