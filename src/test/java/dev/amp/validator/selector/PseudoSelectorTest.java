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

package dev.amp.validator.selector;

import dev.amp.validator.css.TokenType;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Test for {@link PseudoSelector}
 *
 * @author Jacob James
 */

public class PseudoSelectorTest {

    @Test
    public void getValsPseudoSelector() {
        List myList = new ArrayList();
        PseudoSelector alpha = new PseudoSelector(true, "beta", myList);
        alpha.getName();
        Assert.assertEquals(alpha.getName(), "beta");
        Assert.assertEquals(alpha.isClass(), true);
        TokenType charlie = TokenType.PSEUDO_SELECTOR;
        Assert.assertEquals(alpha.getTokenType(), charlie);
    }

}

