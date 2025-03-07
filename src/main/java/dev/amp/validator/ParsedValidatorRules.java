/*
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *  ====================================================================
 */

/*
 * Changes to the original project are Copyright 2019, Verizon Media Inc..
 */

package dev.amp.validator;

import dev.amp.validator.css.ParsedDocCssSpec;
import dev.amp.validator.exception.TagValidationException;
import dev.amp.validator.exception.ValidatorException;
import dev.amp.validator.utils.AttributeSpecUtils;
import dev.amp.validator.utils.DispatchKeyUtils;
import dev.amp.validator.utils.TagSpecUtils;
import org.xml.sax.Attributes;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * This wrapper class provides access to the validation rules.
 *
 * @author nhant01
 * @author GeorgeLuo
 */

public class ParsedValidatorRules {
    /**
     * Constructor.
     *
     * @param htmlFormat          the HTML format.
     * @param ampValidatorManager the AMPValidatorManager instance.
     */
    public ParsedValidatorRules(@Nonnull final ValidatorProtos.HtmlFormat.Code htmlFormat,
                                @Nonnull final AMPValidatorManager ampValidatorManager) {
        this.ampValidatorManager = ampValidatorManager;

        this.htmlFormat = htmlFormat;
        this.parsedTagSpecById = new HashMap<>();
        this.tagSpecByTagName = new HashMap<>();
        this.extTagSpecIdsByExtName = new HashMap<>();
        this.mandatoryTagSpecs = new ArrayList<>();
        this.fullMatchRegexes = new HashMap<>();
        this.fullMatchCaseiRegexes = new HashMap<>();
        this.partialMatchCaseiRegexes = new HashMap<>();

        this.typeIdentifiers = new HashMap<>();
        typeIdentifiers.put("\u26a1", 0);
        typeIdentifiers.put("\u26a1\ufe0f", 0);
        typeIdentifiers.put("amp", 0);
        typeIdentifiers.put("\u26a14ads", 0);
        typeIdentifiers.put("\u26a1\ufe0f4ads", 0);
        typeIdentifiers.put("amp4ads", 0);
        typeIdentifiers.put("\u26a14email", 0);
        typeIdentifiers.put("\u26a1\ufe0f4email", 0);
        typeIdentifiers.put("amp4email", 0);
        typeIdentifiers.put("actions", 0);
        typeIdentifiers.put("transformed", 0);
        typeIdentifiers.put("data-ampdevmode", 0);
        typeIdentifiers.put("data-css-strict", 0);

        expandExtensionSpec();

        this.parsedAttrSpecs = new ParsedAttrSpecs(ampValidatorManager);

        this.parsedCss = new ArrayList<>();
        for (final ValidatorProtos.DocCssSpec cssSpec : this.ampValidatorManager.getRules().getCssList()) {
            this.parsedCss.add(
                    new ParsedDocCssSpec(cssSpec, this.ampValidatorManager.getRules().getDeclarationListList()));
        }

        this.parsedDoc = new ArrayList<>();
        for (final ValidatorProtos.DocSpec docSpec : this.ampValidatorManager.getRules().getDocList()) {
            this.parsedDoc.add(new ParsedDocSpec(docSpec));
        }

        this.tagSpecIdsToTrack = new HashMap<>();
        final int numTags = this.ampValidatorManager.getRules().getTagsList().size();
        for (int tagSpecId = 0; tagSpecId < numTags; ++tagSpecId) {
            final ValidatorProtos.TagSpec tag = this.ampValidatorManager.getRules().getTags(tagSpecId);
            if (!this.isTagSpecCorrectHtmlFormat(tag)) {
                continue;
            }

            if (tag.hasSpecName()) {
                tagSpecNameToSpecId.put(tag.getSpecName(), tagSpecId);
            }

            if (tag.getAlsoRequiresTagWarningList().size() > 0) {
                this.tagSpecIdsToTrack.put(tagSpecId, true);
            }

            for (String otherTag : tag.getAlsoRequiresTagWarningList()) {
                this.tagSpecIdsToTrack.put(otherTag, true);
            }

            if (!tag.getTagName().equals("$REFERENCE_POINT")) {
                if (!(tagSpecByTagName.containsKey(tag.getTagName()))) {
                    this.tagSpecByTagName.put(tag.getTagName(), new TagSpecDispatch());
                }

                final TagSpecDispatch tagnameDispatch = this.tagSpecByTagName.get(tag.getTagName());
                if (tag.hasExtensionSpec()) {
                    // This tag is an extension. Compute and register a dispatch key
                    // for it.
                    String dispatchKey = DispatchKeyUtils.makeDispatchKey(
                            ValidatorProtos.AttrSpec.DispatchKeyType.NAME_VALUE_DISPATCH,
                            AttributeSpecUtils.getExtensionNameAttribute(tag.getExtensionSpec()),
                            tag.getExtensionSpec().getName(), "");
                    tagnameDispatch.registerDispatchKey(dispatchKey, tagSpecId);
                } else {
                    String dispatchKey = this.ampValidatorManager.getDispatchKeyByTagSpecId(tagSpecId);
                    if (dispatchKey == null) {
                        tagnameDispatch.registerTagSpec(tagSpecId);
                    } else {
                        tagnameDispatch.registerDispatchKey(dispatchKey, tagSpecId);
                    }
                }
            }

            if (tag.hasMandatory()) {
                this.mandatoryTagSpecs.add(tagSpecId);
            }
            if (tag.hasExtensionSpec()) {
                List<Integer> tagSpecList;
                if (this.extTagSpecIdsByExtName.containsKey(tag.getExtensionSpec().getName())) {
                   tagSpecList = this.extTagSpecIdsByExtName.get(tag.getExtensionSpec().getName());
                    tagSpecList.add(tagSpecId);
                } else {
                    tagSpecList = new ArrayList<>();
                    tagSpecList.add(tagSpecId);
                    this.extTagSpecIdsByExtName.put(tag.getExtensionSpec().getName(), tagSpecList);
                }
            }
        }

        this.errorCodes = new HashMap<>();
        for (int i = 0; i < this.ampValidatorManager.getRules().getErrorFormatsList().size(); ++i) {
            final ValidatorProtos.ErrorFormat errorFormat =
                    this.ampValidatorManager.getRules().getErrorFormats(i);
            if (errorFormat != null) {
                ErrorCodeMetadata errorCodeMetadata = new ErrorCodeMetadata();
                errorCodeMetadata.setFormat(errorFormat.getFormat());
                errorCodes.put(errorFormat.getCode(), errorCodeMetadata);
            }
        }

        for (int i = 0; i < this.ampValidatorManager.getRules().getErrorSpecificityList().size(); ++i) {
            final ValidatorProtos.ErrorSpecificity errorSpecificity =
                    this.ampValidatorManager.getRules().getErrorSpecificity(i);
            if (errorSpecificity != null) {
                ErrorCodeMetadata errorCodeMetadata = errorCodes.get(errorSpecificity.getCode());
                if (errorCodeMetadata != null) {
                    errorCodeMetadata.setSpecificity(errorSpecificity.getSpecificity());
                }
            }
        }
    }

    /**
     * Find the regex pattern from the map. If found then returns. Otherwise create new pattern. In an event when
     * a pattern syntax exception occurs, escape curly brace and recompile.
     * @param regexMap the regex map
     * @param regex the regex
     * @param isFullMatch if we are looking for a full match
     * @return the pattern
     */
    public Pattern findOrCreatePattern(@Nonnull final Map<String, Pattern> regexMap, @Nonnull final String regex, final boolean isFullMatch) {
        if (regexMap.containsKey(regex)) {
            return regexMap.get(regex);
        }

        Pattern pattern;
        String newRegex = isFullMatch ? ("^(" + regex + ")$") : regex;
        try {
            pattern = Pattern.compile(newRegex);
        } catch (final PatternSyntaxException pse) {
            newRegex = regex.replace("{", "\\{");
            newRegex = isFullMatch ? ("^(" + newRegex + ")$") : newRegex;
            pattern = Pattern.compile(newRegex);
        }

        regexMap.put(regex, pattern);

        return pattern;
    }
    /**
     * Returns full match regex pattern.
     *
     * @param regex the regex.
     * @return returns the full match regex pattern.
     */
    public Pattern getFullMatchRegex(@Nonnull final String regex) {
        return findOrCreatePattern(fullMatchRegexes, regex, true);
    }

    /**
     * Returns full match case insensitive regex pattern.
     *
     * @param regex case insensitive regex.
     * @return returns the full match case insensitive regex pattern.
     */
    public Pattern getFullMatchCaseiRegex(@Nonnull final String regex) {
        return findOrCreatePattern(fullMatchCaseiRegexes, regex, true);
    }

    /**
     * Returns the partial match case insensitive match regex pattern.
     *
     * @param regex the regex.
     * @return returns the partial match case insensitive match regex pattern.
     */
    public Pattern getPartialMatchCaseiRegex(@Nonnull final String regex) {
        return findOrCreatePattern(partialMatchCaseiRegexes, regex, false);
    }

    /**
     * Computes the name for a given reference point.
     * Used in generating error strings.
     *
     * @param referencePoint the reference point.
     * @return returns the compute name for a given reference point.
     * @throws TagValidationException the TagValidationException.
     */
    public String getReferencePointName(@Nonnull final ValidatorProtos.ReferencePoint referencePoint)
            throws TagValidationException {
        // tagSpecName here is actually a number, which was replaced in
        // validator_gen_js.py from the name string, so this works.
        final int tagSpecId =
                ampValidatorManager.getTagSpecIdByReferencePointTagSpecName(referencePoint.getTagSpecName());
        final ParsedTagSpec refPointSpec = this.getByTagSpecId(tagSpecId);
        return TagSpecUtils.getTagSpecName(refPointSpec.getSpec());
    }

    /**
     * Return the ParsedTagSpec given the reference point spec name.
     *
     * @param specName the spec name.
     * @return return the ParsedTagSpec given the reference point spec name.
     * @throws TagValidationException the TagValidationException.
     */
    public ParsedTagSpec getByTagSpecId(final String specName) throws TagValidationException {
        int tagSpecId = this.ampValidatorManager.getTagSpecIdByReferencePointTagSpecName(specName);
        return getByTagSpecId(tagSpecId);
    }

    /**
     * Returns the spec id by spec name.
     *
     * @param specName the spec name.
     * @return returns the spec id if exists.
     */
    public Integer getTagSpecIdBySpecName(@Nonnull final String specName) {
        return tagSpecNameToSpecId.get(specName);
    }

    /**
     * Returns the ParsedTagSpec given the tag spec id.
     *
     * @param id tag spec id.
     * @return returns the ParsedTagSpec.
     * @throws TagValidationException
     */
    public ParsedTagSpec getByTagSpecId(final int id) throws TagValidationException {
        ParsedTagSpec parsed = this.parsedTagSpecById.get(id);
        if (parsed != null) {
            return parsed;
        }
        ValidatorProtos.TagSpec tag = this.ampValidatorManager.getRules().getTags(id);
        if (tag == null) {
            throw new TagValidationException("TagSpec is null for tag spec id " + id);
        }
        if (!isTagSpecCorrectHtmlFormat(tag)) {
            throw new TagValidationException("TagSpec is invalid htmlformat for tag spec id " + id);
        }
        parsed = new ParsedTagSpec(
                this.parsedAttrSpecs,
                TagSpecUtils.shouldRecordTagspecValidated(tag, id, this.tagSpecIdsToTrack), tag,
                id);
        this.parsedTagSpecById.put(id, parsed);
        return parsed;
    }

    /**
     * Returns the tag spec id by reference point tag spec name.
     *
     * @param tagName the reference point tag name.
     * @return returns the tag spec id by reference point tag spec name.
     * @throws TagValidationException the TagValidationException.
     */
    public int getTagSpecIdByReferencePointTagSpecName(@Nonnull final String tagName) throws TagValidationException {
        return this.ampValidatorManager.getTagSpecIdByReferencePointTagSpecName(tagName);
    }

    /**
     * Returns true iff resultA is a better result than resultB.
     *
     * @param resultA a validation result.
     * @param resultB a validation result.
     * @return returns true iff resultA is a better result than resultB.
     * @throws ValidatorException the ValidatorException.
     */
    public boolean betterValidationResultThan(@Nonnull final ValidatorProtos.ValidationResult.Builder resultA,
                                              @Nonnull final ValidatorProtos.ValidationResult.Builder resultB)
            throws ValidatorException {
        if (resultA.getStatus() != resultB.getStatus()) {
            return this.betterValidationStatusThan(resultA.getStatus(), resultB.getStatus());
        }

        // If one of the error sets by error.code is a subset of the other
        // error set's error.codes, use the subset one. It's essentially saying, if
        // you fix these errors that we both complain about, then you'd be passing
        // for my tagspec, but not the other one, regardless of specificity.
        if (this.isErrorSubset(resultB.getErrorsList(), resultA.getErrorsList())) {
            return true;
        }

        if (this.isErrorSubset(resultA.getErrorsList(), resultB.getErrorsList())) {
            return false;
        }

        // Prefer the most specific error found in either set.
        if (this.maxSpecificity(resultA.getErrorsList())
                > this.maxSpecificity(resultB.getErrorsList())) {
            return true;
        }
        if (this.maxSpecificity(resultB.getErrorsList())
                > this.maxSpecificity(resultA.getErrorsList())) {
            return false;
        }

        // Prefer the attempt with the fewest errors if the most specific errors
        // are the same.
        if (resultA.getErrorsCount() < resultB.getErrorsCount()) {
            return true;
        }
        if (resultB.getErrorsCount() < resultA.getErrorsCount()) {
            return false;
        }

        // Equal, so not better than.
        return false;
    }

    /**
     * Checks if maybeTypeIdentifier is contained in rules' typeIdentifiers.
     *
     * @param maybeTypeIdentifier identifier to check
     * @return true iff maybeTypeIdentifier is in typeIdentifiers.
     */
    public boolean isTypeIdentifier(@Nonnull final String maybeTypeIdentifier) {
        return this.typeIdentifiers.containsKey(maybeTypeIdentifier);
    }

    /**
     * Validates type identifiers within a set of attributes, adding
     * ValidationErrors as necessary, and sets type identifiers on
     * ValidationResult.typeIdentifier.
     *
     * @param attrs             sax Attributes object from tag.
     * @param formatIdentifiers html formats
     * @param context           global context of document validation
     * @param validationResult  status of document validation
     */
    public void validateTypeIdentifiers(@Nonnull final Attributes attrs,
                                        @Nonnull final List<String> formatIdentifiers, @Nonnull final Context context,
                                        @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult) {
        boolean hasMandatoryTypeIdentifier = false;
        boolean hasEmailTypeIdentifier = false;
        boolean hasCssStrictTypeIdentifier = false;

        // The named values should match up to `self` and AMP caches listed at
        // https://cdn.ampproject.org/caches.json
        for (int i = 0; i < attrs.getLength(); i++) {
            // Verify this attribute is a type identifier. Other attributes are
            // validated in validateAttributes.
            if (this.isTypeIdentifier(attrs.getLocalName(i))) {
                // Verify this type identifier is allowed for this format.
                if (formatIdentifiers.contains(attrs.getLocalName(i))) {
                    // Only add the type identifier once per representation. That is, both
                    // "⚡" and "amp", which represent the same type identifier.
                    final String typeIdentifier = attrs.getLocalName(i).replace("\u26a1\ufe0f", "amp")
                            .replace("\u26a1", "amp");
                    if (!validationResult.getTypeIdentifierList().contains(typeIdentifier)) {
                        validationResult.addTypeIdentifier(typeIdentifier);
                        context.recordTypeIdentifier(typeIdentifier);
                    }
                    // The type identifier "transformed" is not considered mandatory
                    // unlike other type identifiers.
                    if (!typeIdentifier.equals("transformed")
                            && !typeIdentifier.equals("data-ampdevmode")
                            && !typeIdentifier.equals("data-css-strict")) {
                        hasMandatoryTypeIdentifier = true;
                    }
                    // The type identifier "transformed" has restrictions on it's value.
                    // It must be \w+;v=\d+ (e.g. google;v=1).
                    if ((typeIdentifier.equals("transformed") && !(attrs.getValue(i).equals("")))) {
                        Matcher reResult = TRANSFORMED_VALUE_REGEX.matcher(attrs.getValue(i));
                        if (reResult.matches()) {
                            validationResult.setTransformerVersion(Integer.parseInt(reResult.group(2)));
                        } else {
                            final List<String> params = new ArrayList<>();
                            params.add(attrs.getLocalName(i));
                            params.add("html");
                            params.add(attrs.getValue(i));
                            context.addError(
                                    ValidatorProtos.ValidationError.Code.INVALID_ATTR_VALUE,
                                    context.getLineCol(),
                                    /*params=*/params,
                                    "https://amp.dev/documentation/guides-and-tutorials/learn/spec/amphtml#required-markup",
                                    validationResult);
                        }
                    }
                    if (typeIdentifier.equals("data-ampdevmode")) {
                        // https://github.com/ampproject/amphtml/issues/20974
                        // We always emit an error for this type identifier, but it
                        // suppresses other errors later in the document.
                        context.addError(
                                ValidatorProtos.ValidationError.Code.DEV_MODE_ONLY,
                                context.getLineCol(), /*params=*/new ArrayList<>(), /*url*/ "",
                                validationResult);
                    }
                    if (typeIdentifier.equals("amp4email")) {
                        hasEmailTypeIdentifier = true;
                    }
                    if (typeIdentifier.equals("data-css-strict")) {
                        hasCssStrictTypeIdentifier = true;
                    }
                } else {
                    final List<String> params = new ArrayList<>();
                    params.add(attrs.getLocalName(i));
                    params.add("html");
                    context.addError(
                            ValidatorProtos.ValidationError.Code.DISALLOWED_ATTR,
                            context.getLineCol(), /*params=*/params,
                            "https://amp.dev/documentation/guides-and-tutorials/learn/spec/amphtml#required-markup",
                            validationResult);
                }
            }
        }
        // If AMP Email format and not set to data-css-strict, then issue a warning
        // that not having data-css-strict is deprecated. See b/179798751.
        if (hasEmailTypeIdentifier && !hasCssStrictTypeIdentifier) {
            context.addWarning(
                    ValidatorProtos.ValidationError.Code.AMP_EMAIL_MISSING_STRICT_CSS_ATTR,
                    context.getLineCol(), new ArrayList<String>(),
            "https://github.com/ampproject/amphtml/issues/32587",
                    validationResult);
        }
        if (!hasMandatoryTypeIdentifier) {
            // Missing mandatory type identifier (any AMP variant but "actions" or
            // "transformed").
            final List<String> params = new ArrayList<>();
            params.add(formatIdentifiers.get(0));
            params.add("html");
            context.addError(
                    ValidatorProtos.ValidationError.Code.MANDATORY_ATTR_MISSING,
                    context.getLineCol(), /*params=*/params,
                    "https://amp.dev/documentation/guides-and-tutorials/learn/spec/amphtml#required-markup",
                    validationResult);
        }
    }

    /**
     * Validates the HTML tag for type identifiers.
     *
     * @param htmlTag          the html tag to validate.
     * @param context          global context of document validation
     * @param validationResult status of document validation
     */
    public void validateHtmlTag(@Nonnull final ParsedHtmlTag htmlTag,
                                @Nonnull final Context context,
                                @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult) {
        switch (this.htmlFormat) {
            case AMP:
                this.validateTypeIdentifiers(
                        htmlTag.attrs(), TagSpecUtils.AMP_IDENTIFIERS, context, validationResult);
                break;
            case AMP4ADS:
                this.validateTypeIdentifiers(
                        htmlTag.attrs(), TagSpecUtils.AMP4ADS_IDENTIFIERS, context, validationResult);
                break;
            case AMP4EMAIL:
                this.validateTypeIdentifiers(
                        htmlTag.attrs(), TagSpecUtils.AMP4EMAIL_IDENTIFIERS, context, validationResult);
                break;
            default:
                // fallthrough
        }
    }

    /**
     * Returns the error code specificity.
     *
     * @param errorCode the validation error code.
     * @return returns the error code specificity.
     */
    public int specificity(@Nonnull final ValidatorProtos.ValidationError.Code errorCode) {
        return this.errorCodes.get(errorCode).getSpecificity();
    }

    /**
     * A helper function which allows us to compare two candidate results
     * in validateTag to report the results which have the most specific errors.
     *
     * @param errors a list of validation errors.
     * @return returns maximum value of specificity found in all errors.
     * @throws ValidatorException the TagValidationException.
     */
    public int maxSpecificity(@Nonnull final List<ValidatorProtos.ValidationError> errors) throws ValidatorException {
        int max = 0;
        for (final ValidatorProtos.ValidationError error : errors) {
            if (error.getCode() == null) {
                throw new ValidatorException("Validation error code is null");
            }
            max = Math.max(this.specificity(error.getCode()), max);
        }
        return max;
    }

    /**
     * Returns true iff the error codes in errorsB are a subset of the error
     * codes in errorsA.
     *
     * @param errorsA a list of validation errors.
     * @param errorsB a list of validation errors.
     * @return returns true iff the error codes in errorsB are a subset of the error
     * codes in errorsA.
     */
    public boolean isErrorSubset(@Nonnull final List<ValidatorProtos.ValidationError> errorsA,
                                 @Nonnull final List<ValidatorProtos.ValidationError> errorsB) {
        Map<ValidatorProtos.ValidationError.Code, Integer> codesA = new HashMap<>();
        for (final ValidatorProtos.ValidationError error : errorsA) {
            codesA.put(error.getCode(), 1);
        }

        Map<ValidatorProtos.ValidationError.Code, Integer> codesB = new HashMap<>();
        for (final ValidatorProtos.ValidationError error : errorsB) {
            codesB.put(error.getCode(), 1);
            if (!codesA.containsKey(error.getCode())) {
                return false;
            }
        }

        // Every code in B is also in A. If they are the same, not a subset.
        return codesA.size() > codesB.size();
    }

    /**
     * Returns true iff statusA is a better status than statusB.
     *
     * @param statusA validation result status.
     * @param statusB validation result status.
     * @return returns true iff statusA is a better status than statusB.
     * @throws ValidatorException the ValidatorException.
     */
    public boolean betterValidationStatusThan(@Nonnull final ValidatorProtos.ValidationResult.Status statusA,
                                              @Nonnull final ValidatorProtos.ValidationResult.Status statusB)
            throws ValidatorException {
        // Equal, so not better than.
        if (statusA == statusB) {
            return false;
        }

        // PASS > FAIL > UNKNOWN
        if (statusA == ValidatorProtos.ValidationResult.Status.PASS) {
            return true;
        }

        if (statusB == ValidatorProtos.ValidationResult.Status.PASS) {
            return false;
        }

        if (statusA == ValidatorProtos.ValidationResult.Status.FAIL) {
            return true;
        }

        if (statusA == ValidatorProtos.ValidationResult.Status.UNKNOWN) {
            throw new ValidatorException("Status unknown");
        }

        return false;
    }

    /**
     * Returns a TagSpecDispatch for a give tag name.
     *
     * @param tagName the tag name.
     * @return returns a TagSpecDispatch if found.
     */
    public TagSpecDispatch dispatchForTagName(@Nonnull final String tagName) {
        return this.tagSpecByTagName.get(tagName);
    }

    /**
     * Returns a styles spec url.
     *
     * @return returns a styles spec url.
     */
    public String getStylesSpecUrl() {
        return this.ampValidatorManager.getRules().getStylesSpecUrl();
    }

    /**
     * Returns a template spec url.
     *
     * @return returns a template spec url.
     */
    public String getTemplateSpecUrl() {
        return this.ampValidatorManager.getRules().getTemplateSpecUrl();
    }

    /**
     * Returns the script spec url.
     *
     * @return returns the script spec url.
     */
    public String getScriptSpecUrl() {
        return this.ampValidatorManager.getRules().getScriptSpecUrl();
    }

    /**
     * Returns the list of Css length spec.
     *
     * @return returns the list of Css length spec.
     */
    public List<ParsedDocCssSpec> getCss() {
        return this.parsedCss;
    }

    /**
     * Returns the descendant tag lists.
     *
     * @return returns the descendant tag lists.
     */
    public List<ValidatorProtos.DescendantTagList> getDescendantTagLists() {
        return ampValidatorManager.getDescendantTagLists();
    }

    /**
     * Returns a combined disallowed regex.
     *
     * @param tagSpecId tag spec id.
     * @return returns a combined disallowed regex.
     */
    public String getCombinedDisallowedCdataRegex(final int tagSpecId) {
        return ampValidatorManager.getCombinedDisallowedCdataRegex(tagSpecId);
    }

    /**
     * Emits any validation errors which require a global view
     * (mandatory tags, tags required by other tags, mandatory alternatives).
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException the TagValidationException.
     */
    public void maybeEmitGlobalTagValidationErrors(@Nonnull final Context context,
                                                   @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        this.maybeEmitMandatoryTagValidationErrors(context, validationResult);
        this.maybeEmitRequiresOrExcludesValidationErrors(context, validationResult);
        this.maybeEmitMandatoryAlternativesSatisfiedErrors(
                context, validationResult);
        this.maybeEmitDocSizeErrors(context, validationResult);
        this.maybeEmitCssLengthSpecErrors(context, validationResult);
        this.maybeEmitValueSetMismatchErrors(context, validationResult);
    }

    /**
     * Emits errors when there is a ValueSetRequirement with no matching
     * ValueSetProvision in the document.
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException the TagValidationException.
     */
    public void maybeEmitValueSetMismatchErrors(@Nonnull final Context context,
                                                @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        final Set<String> providedKeys = context.valueSetsProvided();
        for (final String requiredKey : context.valueSetsRequired().keySet()) {
            if (!providedKeys.contains(requiredKey)) {
                context.valueSetsRequired().get(requiredKey);
                for (final ValidatorProtos.ValidationError error : context.valueSetsRequired().get(requiredKey)) {
                    context.addBuiltError(error, validationResult);
                }
            }
        }
    }

    /**
     * Emits errors for css size limitations across entire document.
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException the TagValidationException.
     */
    public void maybeEmitCssLengthSpecErrors(@Nonnull final Context context,
                                             @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        // Only emit an error if there have been inline styles used. Otherwise
        // if there was to be an error it would have been caught by
        // CdataMatcher::Match().
        if (context.getInlineStyleByteSize() == 0) {
            return;
        }

        final int bytesUsed =
                context.getInlineStyleByteSize() + context.getStyleTagByteSize();
        final ParsedDocCssSpec parsedCssSpec = context.matchingDocCssSpec();
        if (parsedCssSpec != null) {
            final ValidatorProtos.DocCssSpec cssSpec = parsedCssSpec.getSpec();
            if (cssSpec.getMaxBytes() != MIN_BYTES && bytesUsed > cssSpec.getMaxBytes()) {
                final List<String> params = new ArrayList<>();
                params.add(String.valueOf(bytesUsed));
                params.add(String.valueOf(cssSpec.getMaxBytes()));
                if (cssSpec.hasMaxBytesIsWarning()) {
                    context.addWarning(
                            ValidatorProtos.ValidationError.Code.STYLESHEET_AND_INLINE_STYLE_TOO_LONG,
                            context.getLineCol(),
                            params,
                            cssSpec.getMaxBytesSpecUrl(),
                            validationResult);
                } else {
                    context.addError(
                            ValidatorProtos.ValidationError.Code.STYLESHEET_AND_INLINE_STYLE_TOO_LONG,
                            context.getLineCol(),
                            params,
                            cssSpec.getMaxBytesSpecUrl(),
                            validationResult);
                }
            }
        }
    }

    /**
     * Emits errors for tags that are specified as mandatory alternatives.
     * Returns false iff context.Progress(result).complete.
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException the TagValidationException.
     */
    public void maybeEmitMandatoryAlternativesSatisfiedErrors(@Nonnull final Context context,
                                                              @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        final List<String> satisfied = context.getMandatoryAlternativesSatisfied();
        /** @type {!Array<string>} */
        final List<String> missing = new ArrayList<>();
        Map<String, String> specUrlsByMissing = new HashMap<>();
        for (final ValidatorProtos.TagSpec tagSpec : this.ampValidatorManager.getRules().getTagsList()) {
            if (!tagSpec.hasMandatoryAlternatives() || !this.isTagSpecCorrectHtmlFormat(tagSpec)) {
                continue;
            }
            final String alternative = tagSpec.getMandatoryAlternatives();
            if (satisfied.indexOf(alternative) == -1) {
                if (!missing.contains(alternative)) {
                    missing.add(alternative);
                    specUrlsByMissing.put(alternative, TagSpecUtils.getTagSpecUrl(tagSpec));
                }
            }
        }
        //sortAndUniquify(missing);
        for (final String tagMissing : missing) {
            final List<String> params = new ArrayList<>();
            params.add(tagMissing);
            context.addError(
                    ValidatorProtos.ValidationError.Code.MANDATORY_TAG_MISSING,
                    context.getLineCol(),
                    params,
                    /* specUrl */ specUrlsByMissing.get(tagMissing),
                    validationResult);
        }
    }

    /**
     * Emits errors for tags that are specified to be mandatory.
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException the TagValidationException.
     */
    public void maybeEmitMandatoryTagValidationErrors(@Nonnull final Context context,
                                                      @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        for (int tagSpecId : this.mandatoryTagSpecs) {
            final ParsedTagSpec parsedTagSpec = this.getByTagSpecId(tagSpecId);
            // Skip TagSpecs that aren't used for these type identifiers.
            if (!parsedTagSpec.isUsedForTypeIdentifiers(
                    context.getTypeIdentifiers())) {
                continue;
            }

            if (!context.getTagspecsValidated().containsKey(tagSpecId)) {
                final ValidatorProtos.TagSpec spec = parsedTagSpec.getSpec();
                final List<String> params = new ArrayList<>();
                params.add(TagSpecUtils.getTagSpecName(spec));
                context.addError(
                        ValidatorProtos.ValidationError.Code.MANDATORY_TAG_MISSING,
                        context.getLineCol(),
                        params,
                        TagSpecUtils.getTagSpecUrl(spec),
                        validationResult);
            }
        }
    }

    /**
     * Emits errors for tags that specify that another tag is also required or
     * a condition is required to be satisfied.
     * Returns false iff context.Progress(result).complete.
     *
     * @param context          the Context.
     * @param validationResult the ValidationResult.
     * @throws TagValidationException
     */
    public void maybeEmitRequiresOrExcludesValidationErrors(@Nonnull final Context context,
                                                            @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult)
            throws TagValidationException {
        for (final int tagSpecId : context.getTagspecsValidated().keySet()) {
            final ParsedTagSpec parsedTagSpec = this.getByTagSpecId(tagSpecId);
            // Skip TagSpecs that aren't used for these type identifiers.
            if (!parsedTagSpec.isUsedForTypeIdentifiers(
                    context.getTypeIdentifiers())) {
                continue;
            }
            for (final String condition : parsedTagSpec.requires()) {
                if (!context.satisfiesCondition(condition)) {
                    final List<String> params = new ArrayList<>();
                    params.add(condition);
                    params.add(TagSpecUtils.getTagSpecName(parsedTagSpec.getSpec()));
                    context.addError(
                            ValidatorProtos.ValidationError.Code.TAG_REQUIRED_BY_MISSING,
                            context.getLineCol(),
                            params,
                            TagSpecUtils.getTagSpecUrl(parsedTagSpec.getSpec()),
                            validationResult);
                }
            }
            for (final String condition : parsedTagSpec.excludes()) {
                if (context.satisfiesCondition(condition)) {
                    final List<String> params = new ArrayList<>();
                    params.add(TagSpecUtils.getTagSpecName(parsedTagSpec.getSpec()));
                    params.add(condition);
                    context.addError(
                            ValidatorProtos.ValidationError.Code.TAG_EXCLUDED_BY_TAG,
                            context.getLineCol(),
                            params,
                            TagSpecUtils.getTagSpecUrl(parsedTagSpec.getSpec()),
                            validationResult);
                }
            }
            for (final String requiresTagWarning : parsedTagSpec.getAlsoRequiresTagWarning()) {
                final Integer tagSpecIdObj = getTagSpecIdBySpecName(requiresTagWarning);
                if (tagSpecIdObj == null || !context.getTagspecsValidated().containsKey(tagSpecIdObj)) {
                    final ParsedTagSpec alsoRequiresTagspec = this.getByTagSpecId(tagSpecIdObj);
                    // If there is an alternative tagspec for extension script tagspecs
                    // that has been validated, then move on to the next
                    // alsoRequiresTagWarning.
                    if (alsoRequiresTagspec.getSpec().hasExtensionSpec() && alsoRequiresTagspec.getSpec().
                            getSpecName().endsWith("extension script")
                            && this.hasValidatedAlternativeTagSpec(
                                    context, alsoRequiresTagspec.getSpec().getExtensionSpec().getName())) {
                        continue;
                    }
                    final List<String> params = new ArrayList<>();
                    params.add(TagSpecUtils.getTagSpecName(alsoRequiresTagspec.getSpec()));
                    params.add(TagSpecUtils.getTagSpecName(parsedTagSpec.getSpec()));
                    context.addWarning(
                            ValidatorProtos.ValidationError.Code.WARNING_TAG_REQUIRED_BY_MISSING,
                            context.getLineCol(),
                            params,
                            TagSpecUtils.getTagSpecUrl(parsedTagSpec.getSpec()),
                            validationResult);
                }
            }
        }

        final ExtensionsContext extensionsCtx = context.getExtensions();
        final List<String> unusedRequired = extensionsCtx.unusedExtensionsRequired();
        for (final String unusedExtensionName : unusedRequired) {
            final List<String> params = new ArrayList<>();
            params.add(unusedExtensionName);
            context.addError(
                    ValidatorProtos.ValidationError.Code.EXTENSION_UNUSED,
                    context.getLineCol(),
                    params,
                    /* specUrl */ "", validationResult);
        }
    }

    /**
     * Emits errors for doc size limitations across entire document.
     *
     * @param context the context to evaluate against
     * @param validationResult to populate
     */
    public void maybeEmitDocSizeErrors(@Nonnull final Context context,
                                       @Nonnull final ValidatorProtos.ValidationResult.Builder validationResult) {
        final ParsedDocSpec parsedDocSpec = context.matchingDocSpec();
        if (parsedDocSpec != null) {
            final int bytesUsed = context.getDocByteSize();
            final ValidatorProtos.DocSpec docSpec = parsedDocSpec.spec();
            if (docSpec.getMaxBytes() != -2 && bytesUsed > docSpec.getMaxBytes()) {

                final List<String> params = new ArrayList<>();
                params.add(String.valueOf(docSpec.getMaxBytes()));
                params.add(String.valueOf(bytesUsed));
                context.addError(
                        ValidatorProtos.ValidationError.Code.DOCUMENT_SIZE_LIMIT_EXCEEDED,
                        context.getLineCol(),
                        params,
                        /* specUrl */ docSpec.getMaxBytesSpecUrl(),
                        validationResult);
            }
        }
    }

    /**
     * Returns true if `spec` is usable for the HTML format these rules are
     * built for.
     *
     * @param docSpec the docSpec to evaluate
     * @return true if `spec` is usable for the HTML format these rules
     */
    public boolean isDocSpecCorrectHtmlFormat(@Nonnull final ValidatorProtos.DocSpec docSpec) {
        for (final ValidatorProtos.HtmlFormat.Code htmlFormatCode : docSpec.getHtmlFormatList()) {
            if (htmlFormatCode == htmlFormat) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns true if `spec` is usable for the HTML format these rules are
     * built for.
     *
     * @param docCssSpec the DocCssSpec.
     * @return Returns true if `spec` is usable for the HTML format these rules are
     * built for.
     */
    public boolean isDocCssSpecCorrectHtmlFormat(@Nonnull final ValidatorProtos.DocCssSpec docCssSpec) {
        for (final ValidatorProtos.HtmlFormat.Code htmlFormatCode : docCssSpec.getHtmlFormatList()) {
            if (htmlFormatCode == htmlFormat) {
                return true;
            }
        }

        return false;
    }

    /**
     * Returns true if TagSpec's html format is the same as this html format.
     *
     * @param tagSpec the TagSpec.
     * @return returns true if TagSpec's html format is the same as this html format.
     */
    private boolean isTagSpecCorrectHtmlFormat(@Nonnull final ValidatorProtos.TagSpec tagSpec) {
        for (final ValidatorProtos.HtmlFormat.Code htmlFormatCode : tagSpec.getHtmlFormatList()) {
            if (htmlFormatCode == htmlFormat) {
                return true;
            }
        }

        return false;
    }

    /**
     * For every tagspec that contains an ExtensionSpec, we add several TagSpec
     * fields corresponding to the data found in the ExtensionSpec.
     * The addition of module/nomodule extensions happens in validator_gen_js.py
     * and are built as proper JavaScript classes. They will also be expanded
     * by this method.
     */
    private void expandExtensionSpec() {
        final int numTags = this.ampValidatorManager.getRules().getTagsList().size();
        for (int tagSpecId = 0; tagSpecId < numTags; ++tagSpecId) {
            ValidatorProtos.TagSpec tagSpec = this.ampValidatorManager.getRules().getTags(tagSpecId);

            if (!tagSpec.hasExtensionSpec()) {
                continue;
            }

            ValidatorProtos.TagSpec.Builder tagSpecBuilder = ValidatorProtos.TagSpec.newBuilder();
            tagSpecBuilder.mergeFrom(tagSpec);

            if (!tagSpec.hasSpecName()) {
                tagSpecBuilder.setSpecName(tagSpec.getTagName() + " extension .js script");
            }

            tagSpecBuilder.setMandatoryParent("HEAD");
            if (tagSpec.getExtensionSpec().hasDeprecatedAllowDuplicates()) {
                tagSpecBuilder.setUniqueWarning(true);
            } else {
                tagSpecBuilder.setUnique(true);
            }

            ValidatorProtos.CdataSpec cdataSpec = ValidatorProtos.CdataSpec.getDefaultInstance();
            cdataSpec = cdataSpec.toBuilder().setWhitespaceOnly(true).build();
            tagSpecBuilder.setCdata(cdataSpec);

            this.ampValidatorManager.getRules().setTags(tagSpecId, tagSpecBuilder.build());
        }
    }

    /**
     * check if tagspec has been validated.
     * @param context the global context
     * @param extName the nname of extension to check for
     * @return true iff one of the alternative tagspec ids has been validated
     */
    private boolean hasValidatedAlternativeTagSpec(@Nonnull final Context context, final String extName) {
        if (extName == null) {
            return false;
        }
        for (final Integer alternativeTagSpecId : this.extTagSpecIdsByExtName.get(extName)) {
            if (context.getTagspecsValidated().containsKey(alternativeTagSpecId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Getter for parsed doc
     *
     * @return this parsedDoc
     */
    public List<ParsedDocSpec> getDoc() {
        return this.parsedDoc;
    }

    /**
     * @return this ParsedAttrSpecs
     */
    public ParsedAttrSpecs getParsedAttrSpecs() {
        return this.parsedAttrSpecs;
    }

    /**
     * AmpValidatorManager.
     */
    private AMPValidatorManager ampValidatorManager;

    /**
     * The HTML format.
     */
    private ValidatorProtos.HtmlFormat.Code htmlFormat;

    /**
     * ParsedTagSpecs in id order.
     */
    private Map<Integer, ParsedTagSpec> parsedTagSpecById;

    /**
     * ParsedTagSpecs keyed by name.
     */
    private Map<String, TagSpecDispatch> tagSpecByTagName;

    /**
     * Tag ids that are mandatory for a document to legally validate.
     */
    private List<Integer> mandatoryTagSpecs;

    /**
     * A cache for full match regex instantiations.
     */
    private Map<String, Pattern> fullMatchRegexes;

    /**
     * A cache for full match case insensitive regex instantiation.
     */
    private Map<String, Pattern> fullMatchCaseiRegexes;

    /**
     * A cache for partial match case insensitive regex instantiation.
     */
    private Map<String, Pattern> partialMatchCaseiRegexes;

    /**
     * Type identifiers which are used to determine the set of validation
     * rules to be applied.
     */
    private Map<String, Integer> typeIdentifiers;

    /**
     * A ParsedAttrSpecs object.
     */
    private ParsedAttrSpecs parsedAttrSpecs;

    /**
     * A tag spec names to track.
     */
    private Map<Object, Boolean> tagSpecIdsToTrack;

    /**
     * ErrorCodeMetadata keyed by error code.
     */
    private Map<ValidatorProtos.ValidationError.Code, ErrorCodeMetadata> errorCodes;

    /**
     * Tag spec name to spec id .
     */
    private Map<String, Integer> tagSpecNameToSpecId = new HashMap<>();

    /**
     * Transformed value regex pattern.
     */
    private static final Pattern TRANSFORMED_VALUE_REGEX = Pattern.compile("^(bing|google|self);v=(\\d+)$");

    /**
     * Minimum bytes length.
     */
    private static final int MIN_BYTES = -2;

    /**
     * this parsed Css specs.
     */
    private List<ParsedDocCssSpec> parsedCss;

    /**
     * this parsed doc specs.
     */
    private List<ParsedDocSpec> parsedDoc;

    /**
     * Extension tagspec ids keyed by extension name
     */
    private Map<String, List<Integer>> extTagSpecIdsByExtName;
}
