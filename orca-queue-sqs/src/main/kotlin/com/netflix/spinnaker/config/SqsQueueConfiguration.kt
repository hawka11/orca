/*
 * Copyright 2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.spinnaker.config

import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.services.sqs.AmazonSQS
import com.amazonaws.services.sqs.AmazonSQSClient
import com.netflix.spinnaker.clouddriver.aws.bastion.BastionConfig
import com.netflix.spinnaker.orca.q.Queue
import com.netflix.spinnaker.orca.q.amazon.SqsQueue
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.*

@Configuration
@ConditionalOnExpression("\${queue.sqs.enabled:false}")
@Import(BastionConfig::class)
@ComponentScan(basePackages = arrayOf("com.netflix.spinnaker.orca.q.amazon"))
@EnableConfigurationProperties(SqsProperties::class)
open class SqsQueueConfiguration {

  @Bean
  open fun amazonSqsClient(awsCredentialsProvider: AWSCredentialsProvider, sqsProperties: SqsProperties): AmazonSQS {
    return AmazonSQSClient.builder()
      .withCredentials(awsCredentialsProvider)
      .withRegion(sqsProperties.region)
      .build()
  }

  @Bean
  @Primary
  open fun sqsQueue(amazonSqs: AmazonSQS, sqsProperties: SqsProperties): Queue = SqsQueue(amazonSqs, sqsProperties)
}
