package org.fiware.iam.tmforum;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import lombok.SneakyThrows;
import org.fiware.iam.til.TrustedIssuersListAdapter;
import org.fiware.iam.tmforum.product.server.api.NotificationListenersClientSideApiTestClient;
import org.fiware.iam.tmforum.product.server.api.NotificationListenersClientSideApiTestSpec;
import org.fiware.iam.tmforum.product.server.model.*;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;

import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.*;


@MicronautTest
class NotificationListenerTest implements NotificationListenersClientSideApiTestSpec {
    @Inject
    public TrustedIssuersListAdapter adapter;

    @MockBean(TrustedIssuersListAdapter.class)
    public TrustedIssuersListAdapter adapter() {
        return mock(TrustedIssuersListAdapter.class);
    }

    @MockBean(OrganizationResolver.class)
    public OrganizationResolver organizationResolver() {
        return mock(OrganizationResolver.class);
    }

    @Inject
    public OrganizationResolver organizationResolver;
    @Inject
    private NotificationListenersClientSideApiTestClient testClient;

    private final static String DID = "did:test:someThing";

    @Test
    @Override
    public void listenToProductOrderCreateEvent201() throws Exception {
        when(organizationResolver.getDID(anyString())).thenReturn(DID);
        doNothing().when(adapter).allowIssuer(DID);
        HttpResponse<EventSubscriptionVO> tmForumRelatedOrganizationId = testClient.listenToProductOrderCreateEvent("", createPayload("tmForumRelatedOrganizationId"));
        Assertions.assertEquals(HttpStatus.CREATED.getCode(), tmForumRelatedOrganizationId.code());
        verify(adapter).allowIssuer(DID);
    }

    @Test
    @Override
    public void listenToProductOrderCreateEvent400() throws Exception {
        HttpResponse response = getOrCatch(() -> testClient.listenToProductOrderCreateEvent("", createPayload("tmForumRelatedOrganizationId", "secondOrga")));
        Assertions.assertEquals(HttpStatus.BAD_REQUEST.getCode(), response.code());
        verifyZeroInteractions(adapter, organizationResolver);
    }

    @SneakyThrows
    private HttpResponse getOrCatch(Callable<HttpResponse> callable) {
        try {
            return callable.call();
        } catch (HttpClientResponseException e) {
            return e.getResponse();
        }
    }

    @Override
    public void listenToProductOrderCreateEvent401() throws Exception {

    }


    @Override
    public void listenToProductOrderCreateEvent403() throws Exception {

    }

    @Override
    public void listenToProductOrderCreateEvent404() throws Exception {

    }

    @Override
    public void listenToProductOrderCreateEvent405() throws Exception {

    }

    @Override
    public void listenToProductOrderCreateEvent409() throws Exception {

    }

    @Override
    public void listenToProductOrderCreateEvent500() throws Exception {

    }

    private ProductOrderCreateEventVO createPayload(String... buyerOrgaIds) {
        ProductOrderVO order = new ProductOrderVO();
        for (String buyerOrgaId : buyerOrgaIds) {
            order.addRelatedPartyItem(new RelatedPartyVO().id(buyerOrgaId));
        }
        return new ProductOrderCreateEventVO().event(new ProductOrderCreateEventPayloadVO().productOrder(order));
    }

    @Override
    public void listenToCancelProductOrderCreateEvent201() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent400() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent401() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent403() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent404() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent405() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent409() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderCreateEvent500() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent201() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent400() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent401() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent403() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent404() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent405() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent409() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderInformationRequiredEvent500() throws Exception {
    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent201() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent400() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent401() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent403() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent404() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent405() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent409() throws Exception {

    }

    @Override
    public void listenToCancelProductOrderStateChangeEvent500() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent201() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent400() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent401() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent403() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent404() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent405() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent409() throws Exception {

    }

    @Override
    public void listenToProductOrderAttributeValueChangeEvent500() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent201() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent400() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent401() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent403() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent404() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent405() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent409() throws Exception {

    }

    @Override
    public void listenToProductOrderDeleteEvent500() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent201() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent400() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent401() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent403() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent404() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent405() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent409() throws Exception {

    }

    @Override
    public void listenToProductOrderInformationRequiredEvent500() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent201() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent400() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent401() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent403() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent404() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent405() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent409() throws Exception {

    }

    @Override
    public void listenToProductOrderStateChangeEvent500() throws Exception {

    }
}