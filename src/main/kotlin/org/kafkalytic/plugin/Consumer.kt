package org.kafkalytic.plugin

import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import java.util.*

class Consumer(project: Project, val topic: String, val props: Properties, val dialog: ConsumeDialog)
    : Task.Backgroundable(project, "Consume from $topic", true) {
    private val LOG = Logger.getInstance(this::class.java)

    override fun run(indicator: ProgressIndicator) {
        val local = Properties(props)

        props.put("group.id", "test");
        props.put("enable.auto.commit", "false");
        props.put("session.timeout.ms", "30000");
        props.put("key.deserializer", Class.forName(dialog.getKeyDeserializer()));
        props.put("value.deserializer", Class.forName(dialog.getValueDeserializer()));
        props.put("max.poll.records",1);

        LOG.info(local.toString())
        LOG.info(props.toString())
        val consumer = KafkaConsumer<Any, Any>(props)
        when (dialog.getMode()) {
            0 -> {
                consumer.subscribe(listOf(topic))
                consume(consumer, dialog.getWaitFor(), dialog.getPolls())
            }
            1 -> {
                consumer.subscribe(listOf(topic))
                consumer.poll(0)
                val assignments = consumer.assignment()
                val endOffsets = consumer.endOffsets(assignments)
                LOG.info("Iterating partitions with offsets $endOffsets")
                endOffsets.forEach{ partition, offset ->
                    consumer.seek(partition, if (dialog.getDecrement() > offset) 0 else offset - dialog.getDecrement())
                }
                consume(consumer, dialog.getDecrement() * endOffsets.size)
            }
            2 -> {
                val partitions = consumer.partitionsFor(topic)
                consumer.assign(partitions.filter { it.partition() == dialog.getPartition() }
                        .map { TopicPartition(topic, it.partition())})
                consumer.seek(TopicPartition(topic, dialog.getPartition()), dialog.getOffset())
                consume(consumer, 1)
            }
        }
        LOG.info("background task complete:$title")
        consumer.unsubscribe()
    }

    protected fun consume(consumer: KafkaConsumer<Any, Any>, howMany : Int, polls: Int = 5) {
        var consumed = 0
        (0..polls).forEach {
            val records = consumer.poll(1000) as ConsumerRecords<Any, Any>
            // Handle new records
            LOG.info("polling:" + records.count())
            records.forEach {
                Notifications.Bus.notify(Notification("Kafkalytic", "topic:$topic",
                        "key:$it.key()"
                        + ", partition:" + it.partition()
                        + ", offset:" + it.offset()
                        + ", message:" + it.value().toString(), NotificationType.INFORMATION));
                consumed++
                if (consumed == howMany) {
                    return
                }
                LOG.info("Consumed:$it.key()")
            }
        }
    }

    override fun onCancel() {
        LOG.info("background task complete:$title")
        super.onCancel()
    }
}