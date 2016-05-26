/*
 * Copyright 2013 OmniFaces.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.omnifaces.cdi.param;

import static java.lang.Boolean.parseBoolean;
import static java.util.Arrays.asList;
import static javax.faces.validator.BeanValidator.DISABLE_DEFAULT_BEAN_VALIDATOR_PARAM_NAME;
import static org.omnifaces.util.Beans.getQualifier;
import static org.omnifaces.util.Faces.evaluateExpressionGet;
import static org.omnifaces.util.Faces.getApplication;
import static org.omnifaces.util.Faces.getInitParameter;
import static org.omnifaces.util.FacesLocal.getMessageBundle;
import static org.omnifaces.util.FacesLocal.getRequestParameterValues;
import static org.omnifaces.util.Messages.createError;
import static org.omnifaces.util.Platform.getBeanValidator;
import static org.omnifaces.util.Platform.isBeanValidationAvailable;
import static org.omnifaces.util.Reflection.instance;
import static org.omnifaces.util.Reflection.setPropertiesWithCoercion;
import static org.omnifaces.util.Utils.coalesce;
import static org.omnifaces.util.Utils.containsByClassName;
import static org.omnifaces.util.Utils.isEmpty;

import java.lang.reflect.Array;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.Set;

import javax.enterprise.context.Dependent;
import javax.enterprise.inject.Produces;
import javax.enterprise.inject.spi.InjectionPoint;
import javax.faces.application.Application;
import javax.faces.application.FacesMessage;
import javax.faces.component.UIComponent;
import javax.faces.component.UIInput;
import javax.faces.context.FacesContext;
import javax.faces.convert.Converter;
import javax.faces.convert.ConverterException;
import javax.faces.validator.BeanValidator;
import javax.faces.validator.RequiredValidator;
import javax.faces.validator.Validator;
import javax.faces.validator.ValidatorException;
import javax.inject.Inject;
import javax.validation.ConstraintViolation;

import org.omnifaces.cdi.Param;

/**
 * Producer for a request parameter as defined by the <code>&#64;</code>{@link Param} annotation.
 *
 * @since 1.6
 * @author Arjan Tijms
 */
@Dependent
public class RequestParameterProducer {

	private static final String DEFAULT_REQUIRED_MESSAGE = "{0}: Value is required";

	@SuppressWarnings("unused") // Workaround for OpenWebBeans not properly passing it as produce() method argument.
	@Inject
	private InjectionPoint injectionPoint;

	@Produces
	@Param
	public <V> ParamValue<V> produce(InjectionPoint injectionPoint) {
		Param param = getQualifier(injectionPoint, Param.class);
		String name = getName(param, injectionPoint);
		String label = getLabel(param, injectionPoint);
		Class<V> targetType = getTargetType(injectionPoint);

		FacesContext context = FacesContext.getCurrentInstance();
		String[] submittedValues = getRequestParameterValues(context, name);
		List<V> convertedValues = getConvertedValues(context, param, label, submittedValues, targetType);

		if (!validateValues(context, param, label, submittedValues, convertedValues, injectionPoint)) {
			convertedValues = null;
		}

		return new ParamValue<>(submittedValues, param, targetType, convertedValues);
	}

	@SuppressWarnings("unchecked")
	static <V> List<V> getConvertedValues(FacesContext context, Param param, String label, String[] submittedValues, Class<?> targetType) {
		List<V> convertedValues = null;
		boolean valid = true;

		if (submittedValues != null) {
			convertedValues = new ArrayList<>();
			UIComponent component = context.getViewRoot();
			Object originalLabel = component.getAttributes().get("label");

			try {
				component.getAttributes().put("label", label);
				Converter converter = getConverter(param, targetType);

				for (String submittedValue : submittedValues) {
					try {
						convertedValues.add((V) (converter != null ? converter.getAsObject(context, component, submittedValue) : submittedValue));
					}
					catch (ConverterException e) {
						valid = false;
						addConverterMessage(context, component, label, submittedValue, e, getConverterMessage(param));
					}
				}
			}
			finally {
				if (originalLabel == null) {
					component.getAttributes().remove("label");
				}
				else {
					component.getAttributes().put("label", originalLabel);
				}
			}
		}

		if (!valid) {
			context.validationFailed();
			return null;
		}

		return convertedValues;
	}

	@SuppressWarnings("unchecked")
	static <V> V[] toArray(List<V> convertedValues, Class<V> targetType) {
		return convertedValues.toArray((V[]) Array.newInstance(targetType.getComponentType(), convertedValues.size()));
	}

	private static <V> boolean validateValues(FacesContext context, Param param, String label, String[] submittedValues, List<V> convertedValues, InjectionPoint injectionPoint) {
		boolean valid = true;

		UIComponent component = context.getViewRoot();
		Object originalLabel = component.getAttributes().get("label");

		try {
			component.getAttributes().put("label", label);

			// Check for required.
			if (param.required() && isEmpty(convertedValues)) {
				valid = false;
				addRequiredMessage(context, component, label, getRequiredMessage(param));
			}

			// Use Bean Validation.
			if (shouldDoBeanValidation(param)) {
				Set<ConstraintViolation<?>> violations = doBeanValidation(convertedValues, injectionPoint);

				for (ConstraintViolation<?> violation : violations) {
					valid = false;
					context.addMessage(component.getClientId(context), createError(violation.getMessage(), label));
				}
			}

			// Use JSF native validators.
			if (convertedValues != null) {
				for (Validator validator : getValidators(param)) {
					int i = 0;

					for (V convertedValue : convertedValues) {
						try {
							validator.validate(context, component, convertedValue);
						}
						catch (ValidatorException e) {
							valid = false;
							addValidatorMessages(context, component, label, submittedValues[i], e, getValidatorMessage(param));
						}

						i++;
					}
				}
			}
		}
		finally {
			if (originalLabel == null) {
				component.getAttributes().remove("label");
			}
			else {
				component.getAttributes().put("label", originalLabel);
			}
		}

		if (!valid) {
			context.validationFailed();
		}

		return valid;
	}

	private static Converter getConverter(Param requestParameter, Class<?> targetType) {

		Class<? extends Converter> converterClass = requestParameter.converterClass();
		String converterName = requestParameter.converter();

		Converter converter = null;

		if (!isEmpty(converterName)) {
			Object expressionResult = evaluateExpressionGet(converterName);
			if (expressionResult instanceof Converter) {
				converter = (Converter) expressionResult;
			} else if (expressionResult instanceof String) {
				converter = getApplication().createConverter((String) expressionResult);
			}
		} else if (!converterClass.equals(Converter.class)) { // Converter.class is default, representing null
			converter = instance(converterClass);
		}

		if (converter == null) {
			try {
				converter = getApplication().createConverter(targetType.isArray() ? targetType.getComponentType() : targetType);
			} catch (Exception e) {
				return null;
			}
		}

		if (converter != null) {
			setPropertiesWithCoercion(converter, getConverterAttributes(requestParameter));
		}

		return converter;
	}

	@SuppressWarnings("unchecked")
	private static <V> Class<V> getTargetType(InjectionPoint injectionPoint) {
		Type type = injectionPoint.getType();
		if (type instanceof ParameterizedType) {
			// Assumes ParamValue now. Needs to be adjusted later.
			return (Class<V>) ((ParameterizedType) type).getActualTypeArguments()[0];
		} else if (type instanceof Class) {
			// Direct injection into class type using dynamic producer
			return  (Class<V>)  type;
		}

		return null;
	}

	private static String getName(Param requestParameter, InjectionPoint injectionPoint) {

		String name = requestParameter.name();

		if (isEmpty(name)) {
			name = injectionPoint.getMember().getName();
		} else {
			name = evaluateExpressionAsString(name);
		}

		return name;
	}

	private static String getLabel(Param requestParameter, InjectionPoint injectionPoint) {

		String label = requestParameter.label();

		if (isEmpty(label)) {
			label = getName(requestParameter, injectionPoint);
		} else {
			label = evaluateExpressionAsString(label);
		}

		return label;
	}

	private static String getValidatorMessage(Param requestParameter) {
		return evaluateExpressionAsString(requestParameter.validatorMessage());
	}

	private static String getConverterMessage(Param requestParameter) {
		return evaluateExpressionAsString(requestParameter.converterMessage());
	}

	private static String getRequiredMessage(Param requestParameter) {
		return evaluateExpressionAsString(requestParameter.requiredMessage());
	}

	private static String evaluateExpressionAsString(String expression) {

		if (isEmpty(expression)) {
			return expression;
		}

		Object expressionResult = evaluateExpressionGet(expression);

		if (expressionResult == null) {
			return null;
		}

		return expressionResult.toString();
	}

	private static boolean shouldDoBeanValidation(Param requestParameter) {

		// If bean validation is explicitly disabled for this instance, immediately return false
		if (requestParameter.disableBeanValidation()) {
			return false;
		}

		// Next check if bean validation has been disabled globally, but only if this hasn't been overridden locally
		if (!requestParameter.overrideGlobalBeanValidationDisabled() && parseBoolean(getInitParameter(DISABLE_DEFAULT_BEAN_VALIDATOR_PARAM_NAME))) {
			return false;
		}

		// For all other cases, the availability of bean validation determines if we attempt bean validation or not.
		return isBeanValidationAvailable();
	}

	private static <V> Set<ConstraintViolation<?>> doBeanValidation(List<V> values, InjectionPoint injectionPoint) {

		Class<?> base = injectionPoint.getBean().getBeanClass();
		String property = injectionPoint.getMember().getName();
		Type type = injectionPoint.getType();
		Class<V> targetType = getTargetType(injectionPoint);

		// Check if the target property in which we are injecting in our special holder/wrapper type
		// ParamValue or not. If it's the latter, pre-wrap our value (otherwise types for bean validation
		// would not match)
		Object valueToValidate = (values == null || targetType.isArray()) ? toArray(values, targetType) : values.get(0);

		if (type instanceof ParameterizedType) {
			Type propertyRawType = ((ParameterizedType) type).getRawType();
			if (propertyRawType.equals(ParamValue.class)) {
				valueToValidate = new ParamValue<>(null, null, null, asList(valueToValidate));
			}
		}

		@SuppressWarnings("rawtypes")
		Set violationsRaw = getBeanValidator().validateValue(base, property, valueToValidate);

		@SuppressWarnings("unchecked")
		Set<ConstraintViolation<?>> violations = violationsRaw;

		return violations;
	}

	private static List<Validator> getValidators(Param requestParameter) {

		List<Validator> validators = new ArrayList<>();

		Class<? extends Validator>[] validatorClasses = requestParameter.validatorClasses();
		String[] validatorNames = requestParameter.validators();

		for (String validatorName : validatorNames) {
			Object validator = evaluateExpressionGet(validatorName);
			if (validator instanceof Validator) {
				validators.add((Validator) validator);
			} else if (validator instanceof String) {
				validators.add(getApplication().createValidator(validatorName));
			}
		}

		for (Class<? extends Validator> validatorClass : validatorClasses) {
			validators.add(instance(validatorClass));
		}

		// Process the default validators

		Application application = getApplication();
		for (Entry<String, String> validatorEntry :	application.getDefaultValidatorInfo().entrySet()) {

			String validatorID = validatorEntry.getKey();
			String validatorClassName = validatorEntry.getValue();

			// Check that the validator ID is not the BeanValidator one which we handle in a special way.
			// And make sure the default validator is not already set manually as well.
			if (!validatorID.equals(BeanValidator.VALIDATOR_ID) && !containsByClassName(validators, validatorClassName)) {
				validators.add(application.createValidator(validatorID));
			}
		}

		// Set the attributes on all instantiated validators. We don't distinguish here
		// which attribute should go to which validator.
		Map<String, Object> validatorAttributes = getValidatorAttributes(requestParameter);
		for (Validator validator : validators) {
			setPropertiesWithCoercion(validator, validatorAttributes);
		}

		return validators;
	}

	private static Map<String, Object> getConverterAttributes(Param requestParameter) {

		Map<String, Object> attributeMap = new HashMap<>();

		Attribute[] attributes = requestParameter.converterAttributes();
		for (Attribute attribute : attributes) {
			attributeMap.put(attribute.name(), evaluateExpressionGet(attribute.value()));
		}

		return attributeMap;
	}

	private static Map<String, Object> getValidatorAttributes(Param requestParameter) {

		Map<String, Object> attributeMap = new HashMap<>();

		Attribute[] attributes = requestParameter.validatorAttributes();
		for (Attribute attribute : attributes) {
			attributeMap.put(attribute.name(), evaluateExpressionGet(attribute.value()));
		}

		return attributeMap;
	}



	private static void addConverterMessage(FacesContext context, UIComponent component, String label, String submittedValue, ConverterException ce, String converterMessage) {
		FacesMessage message = null;

		if (!isEmpty(converterMessage)) {
			message = createError(converterMessage, submittedValue, label);
		} else {
			message = ce.getFacesMessage();
			if (message == null) {
				// If the converter didn't add a FacesMessage, set a generic one.
				message = createError("Conversion failed for {0} because: {1}", submittedValue, ce.getMessage());
			}
		}

		context.addMessage(component.getClientId(context), message);
	}

	private static void addRequiredMessage(FacesContext context, UIComponent component, String label, String requiredMessage) {

		FacesMessage message = null;

		if (!isEmpty(requiredMessage)) {
			message = createError(requiredMessage, null, label);
		} else {
			// (Ab)use RequiredValidator to get the same message that all required attributes are using.
			try {
				new RequiredValidator().validate(context, component, null);
			} catch (ValidatorException ve) {
				message = ve.getFacesMessage();
			}

			if (message == null) {
				// RequiredValidator didn't throw or its exception did not have a message set.
				ResourceBundle messageBundle = getMessageBundle(context);
				String defaultRequiredMessage = (messageBundle != null) ? messageBundle.getString(UIInput.REQUIRED_MESSAGE_ID) : null;
				message = createError(coalesce(defaultRequiredMessage, requiredMessage, DEFAULT_REQUIRED_MESSAGE), label);
			}
		}

		context.addMessage(component.getClientId(context), message);
	}

	private static void addValidatorMessages(FacesContext context, UIComponent component, String label, String submittedValue, ValidatorException ve, String validatorMessage) {

		String clientId = component.getClientId(context);

		if (!isEmpty(validatorMessage)) {
			context.addMessage(clientId, createError(validatorMessage, submittedValue, label));
		} else {
			for (FacesMessage facesMessage : getFacesMessages(ve)) {
				context.addMessage(clientId, facesMessage);
			}
		}
	}

	private static List<FacesMessage> getFacesMessages(ValidatorException ve) {
		List<FacesMessage> facesMessages = new ArrayList<>();
		if (ve.getFacesMessages() != null) {
			facesMessages.addAll(ve.getFacesMessages());
		} else if (ve.getFacesMessage() != null) {
			facesMessages.add(ve.getFacesMessage());
		}

		return facesMessages;
	}

}